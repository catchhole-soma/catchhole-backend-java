package org.monitoring.catchholebackend.domain.relationship.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkPurgeDataRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("가족 관계 후보·확정·현재 근거 API 통합 검증")
class CharacterRelationshipControllerIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenProvider jwt;
    @Autowired CharacterSnapshotAccessor snapshots;
    @Autowired WorkPurgeDataRepository purge;

    private final ObjectMapper json = new ObjectMapper();
    private UUID workId;
    private UUID foreignWorkId;
    private UUID parentId;
    private UUID childId;
    private UUID thirdId;
    private UUID foreignCharacterId;
    private String token;

    @BeforeEach
    void createIsolatedWork() {
        tx.executeWithoutResult(transaction -> {
            Member owner = Member.register(UUID.randomUUID() + "@relationship.test", "test-password", null, "관계 작가");
            Member other = Member.register(UUID.randomUUID() + "@relationship.test", "test-password", null, "다른 작가");
            em.persist(owner);
            em.persist(other);
            Work work = Work.create(owner, "가족 관계 합성 작품", WorkGenre.FANTASY, null);
            Work foreign = Work.create(other, "다른 작품", WorkGenre.FANTASY, null);
            em.persist(work);
            em.persist(foreign);
            workId = work.getId();
            foreignWorkId = foreign.getId();
            parentId = character(work, "부모 A");
            childId = character(work, "자녀 B");
            thirdId = character(work, "인물 C");
            foreignCharacterId = character(foreign, "다른 작품 인물");
            token = jwt.generateAccessToken(owner);
        });
    }

    @Test
    @DisplayName("현재 PROFILE 슬롯과 provenance가 모두 있는 Fact만 관계 입력으로 제공한다")
    void sourcesRequireBothCurrentProfileSlotAndProvenance() throws Exception {
        Source current = source(parentId, "profile.family", "B의 부모", true);
        Source historical = source(parentId, "profile.old_family", "과거 가족 정보", false);
        Source missingSlot = source(parentId, "profile.removed", "삭제된 설정", true);
        removeSlot(parentId, missingSlot.key(), false);
        tx.executeWithoutResult(transaction -> {
            WorkCharacter character = em.find(WorkCharacter.class, parentId);
            character.protectManualSlot(CharacterFactType.PROFILE, current.key());
        });

        JsonNode contexts = getData("/relationship-sources?characterId=" + parentId);

        assertThat(contexts).hasSize(1);
        assertThat(contexts.get(0).path("factId").asText()).isEqualTo(current.factId().toString());
        assertThat(contexts.get(0).path("currentValue").path("factValue").asText()).isEqualTo(current.value());
        assertThat(contexts.get(0).path("contextToken").asText()).isNotBlank();
        assertThat(contexts.get(0).path("hasEvidence").asBoolean()).isFalse();
        assertThat(contexts.get(0).path("manuallyEdited").asBoolean()).isTrue();
        assertThat(contexts.toString()).doesNotContain(historical.factId().toString(), missingSlot.factId().toString());
    }

    @Test
    @DisplayName("부모·자녀의 반대 표현과 반복 확정은 관계 하나에 두 근거를 모은다")
    void inverseParentChildStatementsDeduplicateAndAccumulateEvidence() throws Exception {
        Source parent = source(parentId, "profile.family", "B의 부모", true);
        Source child = source(childId, "profile.family", "A의 자녀", true);
        JsonNode first = create(request(parentId, childId, "PARENT_OF", parent));
        JsonNode second = create(request(childId, parentId, "CHILD_OF", child));

        JsonNode confirmed = confirm(first, 0);
        JsonNode inverse = confirm(second, 0);
        JsonNode repeated = confirm(first, 0);

        String relationshipId = confirmed.path("confirmedRelationshipId").asText();
        assertThat(inverse.path("confirmedRelationshipId").asText()).isEqualTo(relationshipId);
        assertThat(repeated.path("confirmedRelationshipId").asText()).isEqualTo(relationshipId);
        JsonNode relation = getData("/relationships/" + relationshipId);
        assertThat(relation.path("kind").asText()).isEqualTo("PARENT_OF");
        assertThat(relation.path("subjectCharacterId").asText()).isEqualTo(parentId.toString());
        assertThat(relation.path("objectCharacterId").asText()).isEqualTo(childId.toString());
        assertThat(relation.path("validity").asText()).isEqualTo("VALID");
        assertThat(relation.path("evidence")).hasSize(2);
        assertThat(getData("/relationships").path("totalElements").asLong()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SPOUSE", "SIBLING"})
    @DisplayName("배우자와 형제는 한쪽 근거로 표현하며 반대 입력도 같은 대칭 관계로 합친다")
    void symmetricRelationshipsCanonicalizeEndpointOrder(String kind) throws Exception {
        Source first = source(parentId, "profile.family", "B와 가족", true);
        Source second = source(childId, "profile.family", "A와 가족", true);
        JsonNode left = confirm(create(request(parentId, childId, kind, first)), 0);
        JsonNode right = confirm(create(request(childId, parentId, kind, second)), 0);
        assertThat(right.path("confirmedRelationshipId")).isEqualTo(left.path("confirmedRelationshipId"));

        JsonNode relation = getData("/relationships/" + left.path("confirmedRelationshipId").asText());
        List<String> ordered = List.of(parentId.toString(), childId.toString()).stream().sorted().toList();
        assertThat(relation.path("subjectCharacterId").asText()).isEqualTo(ordered.getFirst());
        assertThat(relation.path("objectCharacterId").asText()).isEqualTo(ordered.getLast());
        assertThat(getData("/relationships").path("totalElements").asLong()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNCERTAIN", "NEGATED", "FIGURATIVE", "CONFLICTING"})
    @DisplayName("불확실·부정·비유·충돌 주장은 보류하며 확정 관계로 만들지 않는다")
    void nonAssertedCandidatesRemainHeld(String assertion) throws Exception {
        Source source = source(parentId, "profile.family", "가족 여부를 검토할 내용", true);
        ObjectNode request = request(parentId, childId, "PARENT_OF", source).put("assertion", assertion);
        JsonNode candidate = create(request);

        postJson("/relationship-candidates/" + candidate.path("id").asText() + "/confirm", revision(0))
                .andExpect(status().isConflict());

        assertThat(getData("/relationship-candidates/" + candidate.path("id").asText()).path("status").asText())
                .isEqualTo("PENDING");
        assertThat(getData("/relationships").path("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("미연결 상대는 검토 후보로 남고 사용자가 유효한 인물을 연결한 뒤 확정한다")
    void unresolvedTargetCanBeReviewedAndLinkedBeforeConfirmation() throws Exception {
        Source source = source(parentId, "profile.family", "그 아이의 부모", true);
        ObjectNode request = request(parentId, null, "PARENT_OF", source).put("objectMention", "그 아이");
        JsonNode candidate = create(request);
        postJson(candidatePath(candidate) + "/confirm", revision(0)).andExpect(status().isConflict());

        ObjectNode resolved = request(parentId, childId, "PARENT_OF", source);
        resolved.remove("requestId");
        resolved.put("expectedVersion", 0);
        JsonNode updated = data(putJson(candidatePath(candidate), resolved).andExpect(status().isOk()));

        assertThat(updated.path("version").asLong()).isEqualTo(1);
        assertThat(confirm(updated, 1).path("confirmedRelationshipId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("자기 자신·다른 작품·관계 당사자가 아닌 Fact의 연결은 저장하지 않는다")
    void rejectsSelfCrossWorkAndUnrelatedEvidence() throws Exception {
        Source source = source(parentId, "profile.family", "B의 부모", true);
        postJson("/relationship-candidates", request(parentId, parentId, "PARENT_OF", source))
                .andExpect(status().isBadRequest());
        postJson("/relationship-candidates", request(parentId, foreignCharacterId, "PARENT_OF", source))
                .andExpect(status().is4xxClientError());
        Source unrelated = source(thirdId, "profile.family", "별개의 가족 정보", true);
        postJson("/relationship-candidates", request(parentId, childId, "PARENT_OF", unrelated))
                .andExpect(status().isBadRequest());
        assertThat(getData("/relationships").path("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("출처 토큰을 읽은 뒤 현재값이 바뀌면 후보 생성과 늦은 확정을 거절한다")
    void staleSourceTokensPreventCandidateCreationAndLateConfirmation() throws Exception {
        Source source = source(parentId, "profile.family", "B의 부모", true);
        ObjectNode stale = request(parentId, childId, "PARENT_OF", source);
        JsonNode pending = create(stale);
        changeSlot(parentId, source.key(), "C의 부모");

        stale.put("requestId", UUID.randomUUID().toString());
        postJson("/relationship-candidates", stale).andExpect(status().isConflict());
        postJson(candidatePath(pending) + "/confirm", revision(0)).andExpect(status().isConflict());

        assertThat(getData("/relationships").path("totalElements").asLong()).isZero();
        assertThat(getData(candidatePath(pending)).path("version").asLong()).isZero();
    }

    @Test
    @DisplayName("과거 Fact나 현재 슬롯 없이 남은 provenance로 관계를 되살리지 않는다")
    void historicalAndOrphanedSourcesCannotCreateRelationships() throws Exception {
        Source historical = source(parentId, "profile.old_family", "B의 부모", false);
        ObjectNode historicalRequest = baseRequest(parentId, childId, "PARENT_OF");
        addEvidence(historicalRequest, historical.factId(), "0".repeat(64));
        postJson("/relationship-candidates", historicalRequest).andExpect(status().isBadRequest());

        Source current = source(parentId, "profile.family", "B의 부모", true);
        ObjectNode removedRequest = request(parentId, childId, "PARENT_OF", current);
        removeSlot(parentId, current.key(), false);
        postJson("/relationship-candidates", removedRequest).andExpect(status().is4xxClientError());
        assertThat(getData("/relationships").path("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("같은 요청 ID는 동일 후보를 반환하고 다른 내용으로 재사용하면 충돌한다")
    void candidateRequestIdIsIdempotentButCannotChangeMeaning() throws Exception {
        Source source = source(parentId, "profile.family", "B의 부모", true);
        ObjectNode request = request(parentId, childId, "PARENT_OF", source);
        JsonNode first = create(request);
        JsonNode repeated = create(request);
        assertThat(repeated.path("id")).isEqualTo(first.path("id"));
        request.put("kind", "SPOUSE");
        postJson("/relationship-candidates", request).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("비어 있거나 잘못된 근거·토큰은 검증 실패하고 형식만 맞는 위조 토큰도 저장하지 않는다")
    void malformedEvidenceAndForgedTokensCannotCreateCandidates() throws Exception {
        Source source = source(parentId, "profile.family", "B의 부모", true);
        ObjectNode request = request(parentId, childId, "PARENT_OF", source);
        ObjectNode empty = request.deepCopy();
        empty.putArray("evidence");
        postJson("/relationship-candidates", empty).andExpect(status().isBadRequest());
        ObjectNode missing = request.deepCopy();
        missing.remove("evidence");
        postJson("/relationship-candidates", missing).andExpect(status().isBadRequest());
        ObjectNode nullEvidence = request.deepCopy();
        nullEvidence.putArray("evidence").addNull();
        postJson("/relationship-candidates", nullEvidence).andExpect(status().isBadRequest());
        ObjectNode missingFact = request.deepCopy();
        ((ObjectNode) missingFact.path("evidence").get(0)).putNull("sourceFactId");
        postJson("/relationship-candidates", missingFact).andExpect(status().isBadRequest());

        for (String malformedToken : List.of("", "not-a-token", "a".repeat(63), "A".repeat(64))) {
            ObjectNode malformed = request.deepCopy();
            ((ObjectNode) malformed.path("evidence").get(0)).put("sourceToken", malformedToken);
            postJson("/relationship-candidates", malformed).andExpect(status().isBadRequest());
        }
        ObjectNode noToken = request.deepCopy();
        ((ObjectNode) noToken.path("evidence").get(0)).putNull("sourceToken");
        postJson("/relationship-candidates", noToken).andExpect(status().isBadRequest());
        ObjectNode forgedToken = request.deepCopy();
        ((ObjectNode) forgedToken.path("evidence").get(0)).put("sourceToken", "0".repeat(64));
        postJson("/relationship-candidates", forgedToken).andExpect(status().isConflict());

        assertThat(getData("/relationship-candidates").path("totalElements").asLong()).isZero();
        assertThat(getData("/relationships").path("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("같은 Fact를 새 요청으로 다시 확정해도 관계 근거는 하나이고 후보 감사 기록은 보존한다")
    void repeatedSourceAcrossDistinctCandidatesDeduplicatesRelationshipEvidence() throws Exception {
        Source source = source(parentId, "profile.family", "B의 부모", true);
        JsonNode first = create(request(parentId, childId, "PARENT_OF", source));
        JsonNode second = create(request(parentId, childId, "PARENT_OF", source));
        JsonNode firstConfirmed = confirm(first, 0);
        JsonNode secondConfirmed = confirm(second, 0);

        assertThat(first.path("id")).isNotEqualTo(second.path("id"));
        assertThat(firstConfirmed.path("confirmedRelationshipId")).isEqualTo(secondConfirmed.path("confirmedRelationshipId"));
        String relationPath = "/relationships/" + firstConfirmed.path("confirmedRelationshipId").asText();
        assertThat(getData(relationPath).path("evidence")).hasSize(1);
        assertThat(getData(relationPath + "/evidence")).hasSize(1);
        assertThat(getData("/relationships").path("totalElements").asLong()).isEqualTo(1);
        assertThat(getData("/relationship-candidates").path("totalElements").asLong()).isEqualTo(2);
        for (JsonNode candidate : List.of(first, second)) {
            JsonNode audit = getData(candidatePath(candidate));
            assertThat(audit.path("status").asText()).isEqualTo("CONFIRMED");
            assertThat(audit.path("evidence")).hasSize(1);
        }
    }

    @Test
    @DisplayName("후보 수정은 버전을 증가시키고 오래된 수정은 거절하며 제외 재요청은 멱등이다")
    void candidateRevisionProtectsEditsAndDismissalIsIdempotent() throws Exception {
        Source source = source(parentId, "profile.family", "B의 부모", true);
        JsonNode candidate = create(request(parentId, childId, "PARENT_OF", source));
        ObjectNode edit = request(parentId, childId, "PARENT_OF", source);
        edit.remove("requestId");
        edit.put("expectedVersion", 0).put("subtype", "BIOLOGICAL");
        JsonNode changed = data(putJson(candidatePath(candidate), edit).andExpect(status().isOk()));
        assertThat(changed.path("version").asLong()).isEqualTo(1);
        putJson(candidatePath(candidate), edit).andExpect(status().isConflict());

        JsonNode dismissed = data(postJson(candidatePath(candidate) + "/dismiss", revision(1)).andExpect(status().isOk()));
        JsonNode repeated = data(postJson(candidatePath(candidate) + "/dismiss", revision(1)).andExpect(status().isOk()));
        assertThat(dismissed.path("status").asText()).isEqualTo("DISMISSED");
        assertThat(repeated.path("version")).isEqualTo(dismissed.path("version"));
        postJson(candidatePath(candidate) + "/confirm", revision(2)).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("근거 하나가 사라져도 다른 현재 근거를 유지하고 마지막 근거가 사라지면 재검토한다")
    void validityUsesRemainingCurrentSupportWithoutDeletingHistory() throws Exception {
        Source parent = source(parentId, "profile.family", "B의 부모", true);
        Source child = source(childId, "profile.family", "A의 자녀", true);
        JsonNode confirmed = confirm(create(request(parentId, childId, "PARENT_OF", parent)), 0);
        confirm(create(request(childId, parentId, "CHILD_OF", child)), 0);
        String path = "/relationships/" + confirmed.path("confirmedRelationshipId").asText();

        removeSlot(parentId, parent.key(), true);
        assertThat(getData(path).path("validity").asText()).isEqualTo("VALID");
        removeSlot(childId, child.key(), true);
        JsonNode unsupported = getData(path);
        assertThat(unsupported.path("validity").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(unsupported.path("evidence")).hasSize(2);
        CharacterFact parentHistory = tx.execute(transaction -> em.find(CharacterFact.class, parent.factId()));
        CharacterFact childHistory = tx.execute(transaction -> em.find(CharacterFact.class, child.factId()));
        assertThat(parentHistory).isNotNull();
        assertThat(childHistory).isNotNull();
    }

    @Test
    @DisplayName("인물 보관은 관계를 보관 상태로 표시하고 복구하면 현재 근거를 재검사한다")
    void archiveAndRestoreDoNotDestroyRelationshipEvidence() throws Exception {
        Source source = source(parentId, "profile.family", "B의 부모", true);
        JsonNode confirmed = confirm(create(request(parentId, childId, "PARENT_OF", source)), 0);
        String path = "/relationships/" + confirmed.path("confirmedRelationshipId").asText();
        tx.executeWithoutResult(transaction -> em.find(WorkCharacter.class, childId).archive());
        assertThat(getData(path).path("validity").asText()).isEqualTo("ARCHIVED_ENDPOINT");
        tx.executeWithoutResult(transaction -> em.find(WorkCharacter.class, childId).restore());
        assertThat(getData(path).path("validity").asText()).isEqualTo("VALID");
        assertThat(getData(path).path("evidence")).hasSize(1);
    }

    @Test
    @DisplayName("확정 관계 정정은 새 관계를 만들고 기존 관계를 철회하며 오래된 철회 요청을 거절한다")
    void correctingRelationshipPreservesOldRelationAndUsesRevision() throws Exception {
        Source source = source(parentId, "profile.family", "B의 가족", true);
        JsonNode confirmed = confirm(create(request(parentId, childId, "PARENT_OF", source)), 0);
        String oldId = confirmed.path("confirmedRelationshipId").asText();
        long version = getData("/relationships/" + oldId).path("version").asLong();
        ObjectNode correction = request(parentId, childId, "SIBLING", source).put("expectedVersion", version);
        JsonNode replacement = data(putJson("/relationships/" + oldId, correction).andExpect(status().isOk()));
        assertThat(replacement.path("id").asText()).isNotEqualTo(oldId);
        assertThat(replacement.path("kind").asText()).isEqualTo("SIBLING");
        assertThat(getData("/relationships/" + oldId).path("validity").asText()).isEqualTo("WITHDRAWN");

        String newPath = "/relationships/" + replacement.path("id").asText() + "/withdraw";
        long newVersion = replacement.path("version").asLong();
        postJson(newPath, revision(newVersion + 1)).andExpect(status().isConflict());
        JsonNode withdrawn = data(postJson(newPath, revision(newVersion)).andExpect(status().isOk()));
        JsonNode repeated = data(postJson(newPath, revision(newVersion)).andExpect(status().isOk()));
        assertThat(withdrawn.path("validity").asText()).isEqualTo("WITHDRAWN");
        assertThat(repeated.path("version")).isEqualTo(withdrawn.path("version"));
    }

    @Test
    @DisplayName("동일한 관계로 정정해도 새 기록을 만들며 이전 후보 확정 재시도가 철회를 되돌리지 않는다")
    void sameIdentityCorrectionAndOriginalConfirmationReplayCannotResurrectWithdrawnRelation() throws Exception {
        Source source = source(parentId, "profile.family", "B의 부모", true);
        JsonNode originalCandidate = create(request(parentId, childId, "PARENT_OF", source));
        JsonNode confirmed = confirm(originalCandidate, 0);
        String oldPath = "/relationships/" + confirmed.path("confirmedRelationshipId").asText();
        JsonNode old = getData(oldPath);
        ObjectNode correction = request(parentId, childId, "PARENT_OF", source)
                .put("expectedVersion", old.path("version").asLong());

        JsonNode replacement = data(putJson(oldPath, correction).andExpect(status().isOk()));
        JsonNode correctionReplay = data(putJson(oldPath, correction).andExpect(status().isOk()));
        JsonNode confirmationReplay = confirm(originalCandidate, 0);

        assertThat(replacement.path("id")).isNotEqualTo(old.path("id"));
        assertThat(correctionReplay.path("id")).isEqualTo(replacement.path("id"));
        assertThat(correctionReplay.path("version")).isEqualTo(replacement.path("version"));
        assertThat(confirmationReplay.path("confirmedRelationshipId")).isEqualTo(old.path("id"));
        assertThat(getData(oldPath).path("validity").asText()).isEqualTo("WITHDRAWN");
        assertThat(getData("/relationships/" + replacement.path("id").asText()).path("validity").asText())
                .isEqualTo("VALID");
        assertThat(getData("/relationships").path("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("현재 관계와 명시적인 과거 관계는 관측 회차를 사건 시점으로 추정하지 않고 분리한다")
    void temporalScopeAndLabelRemainDistinctFromCurrentSourceFact() throws Exception {
        Source source = source(parentId, "profile.family", "B와 이혼한 뒤 재혼했다", true);
        ObjectNode former = request(parentId, childId, "SPOUSE", source)
                .put("temporalScope", "HISTORICAL").put("timeLabel", "재혼 이전");
        JsonNode past = confirm(create(former), 0);
        JsonNode present = confirm(create(request(parentId, childId, "SPOUSE", source)), 0);
        assertThat(past.path("confirmedRelationshipId")).isNotEqualTo(present.path("confirmedRelationshipId"));
        JsonNode pastRelation = getData("/relationships/" + past.path("confirmedRelationshipId").asText());
        assertThat(pastRelation.path("temporalScope").asText()).isEqualTo("HISTORICAL");
        assertThat(pastRelation.path("timeLabel").asText()).isEqualTo("재혼 이전");
        assertThat(pastRelation.path("visibleInFamilyTree").asBoolean()).isFalse();
        assertThat(getData("/relationships/" + present.path("confirmedRelationshipId").asText())
                .path("visibleInFamilyTree").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("조상으로 되돌아가는 부모 관계는 순환으로 보류하고 기존 두 관계는 보존한다")
    void parentCycleCannotBeConfirmed() throws Exception {
        Source a = source(parentId, "profile.family", "B의 부모", true);
        Source b = source(childId, "profile.family", "C의 부모", true);
        Source c = source(thirdId, "profile.family", "A의 부모라는 상충 정보", true);
        confirm(create(request(parentId, childId, "PARENT_OF", a)), 0);
        confirm(create(request(childId, thirdId, "PARENT_OF", b)), 0);
        JsonNode cycle = create(request(thirdId, parentId, "PARENT_OF", c));

        postJson(candidatePath(cycle) + "/confirm", revision(0)).andExpect(status().isConflict());

        assertThat(getData("/relationships").path("totalElements").asLong()).isEqualTo(2);
        assertThat(getData(candidatePath(cycle)).path("status").asText()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("친생 부모와 입양 부모를 함께 허용하며 관계 종류에 맞지 않는 세부 유형은 거절한다")
    void distinctParentSubtypesCanCoexistWithoutInventingRestrictions() throws Exception {
        Source biological = source(parentId, "profile.family", "B의 친생 부모", true);
        Source adoptive = source(thirdId, "profile.family", "B의 입양 부모", true);
        confirm(create(request(parentId, childId, "PARENT_OF", biological).put("subtype", "BIOLOGICAL")), 0);
        confirm(create(request(thirdId, childId, "PARENT_OF", adoptive).put("subtype", "ADOPTIVE")), 0);
        postJson("/relationship-candidates", request(parentId, childId, "SPOUSE", biological).put("subtype", "BIOLOGICAL"))
                .andExpect(status().isBadRequest());

        assertThat(getData("/relationships").path("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("동시에 확정한 동일 관계는 작품 잠금 아래 하나의 관계와 두 근거를 남긴다")
    void concurrentConfirmationsCreateOneRelationship() throws Exception {
        Source parent = source(parentId, "profile.family", "B의 부모", true);
        Source child = source(childId, "profile.family", "A의 자녀", true);
        JsonNode first = create(request(parentId, childId, "PARENT_OF", parent));
        JsonNode second = create(request(childId, parentId, "CHILD_OF", child));
        CountDownLatch start = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = threads.submit(() -> { start.await(); return confirm(first, 0); });
            var b = threads.submit(() -> { start.await(); return confirm(second, 0); });
            start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS).path("confirmedRelationshipId"))
                    .isEqualTo(b.get(20, TimeUnit.SECONDS).path("confirmedRelationshipId"));
        }
        assertThat(getData("/relationships").path("totalElements").asLong()).isEqualTo(1);
        assertThat(getData("/relationships").path("content").get(0).path("evidence")).hasSize(2);
    }

    @Test
    @DisplayName("인증 없는 요청과 다른 회원 작품은 관계·근거를 노출하지 않는다")
    void authenticatedWorkOwnershipProtectsEveryReadSurface() throws Exception {
        Source source = source(parentId, "profile.family", "B의 부모", true);
        JsonNode confirmed = confirm(create(request(parentId, childId, "PARENT_OF", source)), 0);
        String id = confirmed.path("confirmedRelationshipId").asText();
        mvc.perform(get(base() + "/relationships")).andExpect(status().isUnauthorized());
        for (String path : List.of("/relationships", "/relationship-candidates", "/relationship-sources?characterId=" + foreignCharacterId,
                "/relationships/" + id, "/relationships/" + id + "/evidence")) {
            mvc.perform(get("/api/v1/works/" + foreignWorkId + path).header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("작품 영구 삭제는 관계·후보·근거를 먼저 정리해 Fact와 작품 FK를 안전하게 삭제한다")
    void workPurgeDeletesRelationshipGraphBeforeFacts() throws Exception {
        Source source = source(parentId, "profile.family", "B의 부모", true);
        confirm(create(request(parentId, childId, "PARENT_OF", source)), 0);
        create(request(parentId, null, "SPOUSE", source).put("objectMention", "미상 상대"));
        jdbc.execute("create table if not exists episode_chunks (id uuid primary key, episode_id uuid not null)");

        tx.executeWithoutResult(transaction -> purge.purgeWorkData(workId));

        assertThat(jdbc.queryForObject("select count(*) from works where id = ?", Long.class, workId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from character_facts where character_id = ?", Long.class, parentId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from works where id = ?", Long.class, foreignWorkId)).isEqualTo(1);
    }

    private UUID character(Work work, String name) {
        WorkCharacter character = WorkCharacter.create(work, name, null, null, null, null, null, null, null, null, null);
        em.persist(character);
        return character.getId();
    }

    private Source source(UUID characterId, String key, String value, boolean current) {
        return tx.execute(transaction -> {
            WorkCharacter character = em.find(WorkCharacter.class, characterId);
            ObjectNode valueJson = json.createObjectNode().put("value", value);
            CharacterFact fact = CharacterFact.createManual(character, CharacterFactType.PROFILE, key, value, valueJson);
            em.persist(fact);
            if (current) {
                CharacterSnapshotSlot slot = new CharacterSnapshotSlot(CharacterFactType.PROFILE, key);
                var entries = new LinkedHashMap<>(snapshots.read(character));
                entries.put(slot, new CharacterSnapshotEntry(slot, value, valueJson, true));
                snapshots.replace(character, entries, true);
                em.persist(CharacterSnapshotSource.create(character, CharacterFactType.PROFILE, key, fact, 0));
            }
            return new Source(fact.getId(), characterId, key, value);
        });
    }

    private void changeSlot(UUID characterId, String key, String value) {
        tx.executeWithoutResult(transaction -> {
            WorkCharacter character = em.find(WorkCharacter.class, characterId);
            var entries = new LinkedHashMap<>(snapshots.read(character));
            CharacterSnapshotSlot slot = new CharacterSnapshotSlot(CharacterFactType.PROFILE, key);
            entries.put(slot, new CharacterSnapshotEntry(slot, value, json.createObjectNode().put("value", value), true));
            snapshots.replace(character, entries, false);
        });
    }

    private void removeSlot(UUID characterId, String key, boolean removeProvenance) {
        tx.executeWithoutResult(transaction -> {
            WorkCharacter character = em.find(WorkCharacter.class, characterId);
            var entries = new LinkedHashMap<>(snapshots.read(character));
            entries.remove(new CharacterSnapshotSlot(CharacterFactType.PROFILE, key));
            snapshots.replace(character, entries, removeProvenance);
            if (removeProvenance) {
                em.createQuery("delete from CharacterSnapshotSource source where source.workCharacter.id = :id and source.factKey = :key")
                        .setParameter("id", characterId).setParameter("key", key).executeUpdate();
            }
        });
    }

    private ObjectNode request(UUID subject, UUID object, String kind, Source source) throws Exception {
        ObjectNode request = baseRequest(subject, object, kind);
        JsonNode contexts = getData("/relationship-sources?characterId=" + source.characterId());
        String contextToken = null;
        for (JsonNode context : contexts) {
            if (context.path("factId").asText().equals(source.factId().toString())) {
                contextToken = context.path("contextToken").asText();
            }
        }
        assertThat(contextToken).as("현재 Fact의 관계 입력 토큰").isNotBlank();
        addEvidence(request, source.factId(), contextToken);
        return request;
    }

    private ObjectNode baseRequest(UUID subject, UUID object, String kind) {
        ObjectNode request = json.createObjectNode()
                .put("requestId", UUID.randomUUID().toString())
                .put("subjectCharacterId", subject.toString())
                .put("kind", kind).put("subtype", "UNSPECIFIED")
                .put("temporalScope", "CURRENT").put("assertion", "ASSERTED");
        if (object == null) request.putNull("objectCharacterId");
        else request.put("objectCharacterId", object.toString());
        return request;
    }

    private void addEvidence(ObjectNode request, UUID fact, String token) {
        request.putArray("evidence").addObject().put("sourceFactId", fact.toString()).put("sourceToken", token);
    }

    private JsonNode create(ObjectNode request) throws Exception {
        return data(postJson("/relationship-candidates", request).andExpect(status().is2xxSuccessful()));
    }

    private JsonNode confirm(JsonNode candidate, long expectedVersion) throws Exception {
        return data(postJson(candidatePath(candidate) + "/confirm", revision(expectedVersion)).andExpect(status().isOk()));
    }

    private String candidatePath(JsonNode candidate) {
        return "/relationship-candidates/" + candidate.path("id").asText();
    }

    private ObjectNode revision(long expectedVersion) {
        return json.createObjectNode().put("expectedVersion", expectedVersion);
    }

    private JsonNode getData(String path) throws Exception {
        return data(mvc.perform(get(base() + path).header(HttpHeaders.AUTHORIZATION, bearer())).andExpect(status().isOk()));
    }

    private ResultActions postJson(String path, JsonNode body) throws Exception {
        return mvc.perform(post(base() + path).header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }

    private ResultActions putJson(String path, JsonNode body) throws Exception {
        return mvc.perform(put(base() + path).header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsByteArray()).path("data");
    }

    private String base() { return "/api/v1/works/" + workId; }
    private String bearer() { return "Bearer " + token; }
    private record Source(UUID factId, UUID characterId, String key, String value) {}
}
