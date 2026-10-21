package org.monitoring.catchholebackend.domain.relationship.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.character.dto.request.CharacterUpdateRequest;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSourceManager;
import org.monitoring.catchholebackend.domain.character.service.CharacterService;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipCandidateCreateRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipEvidenceRequest;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipCandidate;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipEvidence;
import org.monitoring.catchholebackend.domain.relationship.processor.ExplicitFamilyRelationshipParser;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipAutomationProcessor;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipProcessingScheduler;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipRules;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipSourceResolver;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipProcessingInput;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipProcessingStateRepository;
import org.monitoring.catchholebackend.domain.relationship.service.RelationshipProcessingService;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipAssertion;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkPurgeDataRepository;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("현재 가족 설정의 비동기 규칙 처리·검토·재처리 통합 계약")
class RelationshipProcessingIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenProvider jwt;
    @Autowired CharacterSnapshotAccessor snapshots;
    @Autowired CharacterSnapshotSourceManager sourceManager;
    @Autowired CharacterService characterService;
    @Autowired RelationshipProcessingService processing;
    @Autowired RelationshipProcessingStateRepository states;
    @Autowired WorkRepository works;
    @Autowired WorkPurgeDataRepository purge;
    @Autowired RelationshipRules relationshipRules;
    @Autowired RelationshipSourceResolver relationshipSources;
    @Autowired ExplicitFamilyRelationshipParser familyParser;
    @MockitoSpyBean RelationshipAutomationProcessor automationProcessor;

    private final ObjectMapper json = new ObjectMapper();
    private Long memberId;
    private UUID workId;
    private UUID foreignWorkId;
    private UUID aria;
    private UUID borin;
    private UUID celine;
    private String token;

    @BeforeEach
    void createWork() {
        tx.executeWithoutResult(transaction -> {
            Member owner = Member.register(UUID.randomUUID() + "@processing.test", "test-password", null, "관계 작가");
            Member other = Member.register(UUID.randomUUID() + "@processing.test", "test-password", null, "다른 작가");
            em.persist(owner);
            em.persist(other);
            Work work = Work.create(owner, "규칙 처리 합성 작품", WorkGenre.FANTASY, null);
            Work foreign = Work.create(other, "다른 작품", WorkGenre.FANTASY, null);
            em.persist(work);
            em.persist(foreign);
            memberId = owner.getId();
            workId = work.getId();
            foreignWorkId = foreign.getId();
            aria = character(work, "아리아");
            borin = character(work, "보린");
            celine = character(work, "셀린");
            token = jwt.generateAccessToken(owner);
        });
    }

    @Test
    @DisplayName("완결된 복합 가족 설정은 등록 관계와 미등록 자녀를 구분하고 재처리해도 중복하지 않는다")
    void completeCompoundFamilySettingPreservesSourcesAndIdempotency() throws Exception {
        String text = "보린의 아내이자 셀린과 미등록자녀의 어머니";
        source(aria, "profile.가족_관계", text, true);
        processCurrentInput();

        var firstCandidates = candidates();
        var firstRelations = relations();
        assertThat(firstCandidates).hasSize(3);
        assertThat(firstRelations).hasSize(2).allSatisfy(relation ->
                assertThat(relation.path("evidence").get(0).path("currentValue").toString()).contains(text));
        assertThat(firstCandidates.stream().filter(candidate -> candidate.path("objectMention").asText().equals("미등록자녀")))
                .singleElement().satisfies(candidate -> {
                    assertThat(candidate.path("status").asText()).isEqualTo("PENDING");
                    assertThat(candidate.path("assertion").asText()).isEqualTo("ASSERTED");
                    assertThat(candidate.path("kind").asText()).isEqualTo("PARENT_OF");
                });
        assertThat(getData("/family-tree").path("edges")).hasSize(3);
        assertThat(getData("/family-tree").path("nodes")).hasSize(4);

        processing.requestProcessing(memberId, workId);
        processCurrentInput();
        assertThat(candidates().stream().map(candidate -> candidate.path("id").asText()).toList())
                .containsExactlyInAnyOrderElementsOf(firstCandidates.stream().map(candidate -> candidate.path("id").asText()).toList());
        assertThat(relations().stream().map(relation -> relation.path("id").asText()).toList())
                .containsExactlyInAnyOrderElementsOf(firstRelations.stream().map(relation -> relation.path("id").asText()).toList());
    }

    @Test
    @DisplayName("지원 밖 친족이 섞인 복문은 읽힌 부모 관계도 확정하거나 가계도에 표시하지 않는다")
    void unsupportedCompoundContextNeverCreatesAnAssertedSubset() throws Exception {
        source(aria, "profile.가족_관계", "보린의 아들이며 셀린의 사촌", true);
        processCurrentInput();

        assertThat(relations()).isEmpty();
        assertThat(candidates()).allSatisfy(candidate -> {
            assertThat(candidate.path("status").asText()).isEqualTo("PENDING");
            assertThat(candidate.path("assertion").asText()).isNotEqualTo("ASSERTED");
        });
        assertThat(getData("/family-tree").path("edges")).isEmpty();
    }

    @Test
    @DisplayName("소유자 이름으로 끝나는 자녀 명사구는 미등록 부모와 명시된 딸 호칭을 표시한다")
    void exactOwnerSuffixDisplaysUnregisteredParentWithoutCreatingCharacter() throws Exception {
        source(aria, "profile.정식_호칭", "미등록부모의 둘째 딸 아리아", true);
        processCurrentInput();

        var candidate = only(candidates());
        assertThat(candidate.path("objectMention").asText()).isEqualTo("미등록부모");
        assertThat(candidate.path("kind").asText()).isEqualTo("CHILD_OF");
        assertThat(candidate.path("assertion").asText()).isEqualTo("ASSERTED");
        assertThat(relations()).isEmpty();
        var tree = getData("/family-tree");
        assertThat(tree.path("edges")).hasSize(1);
        assertThat(tree.path("edges").get(0).path("objectRole").asText()).isEqualTo("DAUGHTER");
        assertThat(jdbc.queryForObject("select count(*) from characters where work_id = ?", Long.class, workId)).isEqualTo(3);
    }

    @Test
    @DisplayName("소유자 이름이 명시된 5대손은 조상 간선 한 건과 원문 근거만 보존하며 재처리도 멱등하다")
    void explicitNumberedDescendantStoresSingleCanonicalEdge() throws Exception {
        String text = "아리아의 5대손 보린";
        source(borin, "profile.특징", text, true);
        processCurrentInput();
        JsonNode relation = only(relations());
        assertThat(relation.path("kind").asText()).isEqualTo("ANCESTOR_OF");
        assertThat(relation.path("subjectCharacterId").asText()).isEqualTo(aria.toString());
        assertThat(relation.path("objectCharacterId").asText()).isEqualTo(borin.toString());
        assertThat(relation.path("generationGap").asInt()).isEqualTo(5);
        assertThat(relation.path("temporalScope").asText()).isEqualTo("CURRENT");
        assertThat(relation.path("evidence").get(0).path("currentValue").toString()).contains(text);
        assertThat(only(candidates()).path("kind").asText()).isEqualTo("DESCENDANT_OF");
        assertThat(only(candidates()).path("generationGap").asInt()).isEqualTo(5);
        assertThat(getData("/family-tree").path("edges")).hasSize(1);
        assertThat(getData("/family-tree").path("nodes")).hasSize(2);
        assertThat(jdbc.queryForObject("select count(*) from characters where work_id = ?", Long.class, workId)).isEqualTo(3);
        processing.requestProcessing(memberId, workId);
        processCurrentInput();
        assertThat(only(relations()).path("id")).isEqualTo(relation.path("id"));
        assertThat(candidates()).hasSize(1);
    }

    @Test
    @DisplayName("세대 수 없는 조상과 후손의 역방향 명제는 null 세대 관계 하나로 합친다")
    void genericAncestryDeduplicatesAndKeepsUnknownGap() throws Exception {
        source(aria, "profile.family", "후손: 보린", true);
        source(borin, "profile.family", "아리아의 후손", true);
        processCurrentInput();
        var relation = only(relations());
        assertThat(relation.path("kind").asText()).isEqualTo("ANCESTOR_OF");
        assertThat(relation.path("generationGap").isNull()).isTrue();
        assertThat(relation.path("evidence")).hasSize(2);
        assertThat(candidates()).hasSize(2);
    }

    @Test
    @DisplayName("직접 부모와 먼 조상으로 구성된 순환도 모든 자동 명제를 보류한다")
    void mixedParentAndAncestorCyclesAreHeld() throws Exception {
        source(aria, "profile.family", "자녀: 보린", true);
        source(borin, "profile.family", "후손: 셀린", true);
        source(celine, "profile.family", "후손: 아리아", true);
        processCurrentInput();
        assertThat(relations()).isEmpty();
        assertThat(candidates()).hasSize(3).allSatisfy(candidate ->
                assertThat(candidate.path("reviewReasons").toString()).contains("ANCESTRY_CYCLE"));
    }

    @Test
    @DisplayName("같은 조상의 서로 다른 명시 세대 수는 조용히 합치지 않고 검토한다")
    void conflictingGenerationCountsAreHeld() throws Exception {
        source(borin, "profile.family", "아리아의 5대손", true);
        source(borin, "profile.특징", "아리아의 7대손", true);
        processCurrentInput();
        assertThat(relations()).isEmpty();
        assertThat(candidates()).hasSize(2).allSatisfy(candidate ->
                assertThat(candidate.path("reviewReasons").toString()).contains("CONFLICTING"));
    }

    @ParameterizedTest(name = "{0}, {1}")
    @CsvSource({"DESCENDANT_OF,0", "ANCESTOR_OF,-1", "PARENT_OF,5", "SPOUSE,5", "SIBLING,5"})
    @DisplayName("세대 수는 조상·후손의 양의 정수일 때만 요청에 허용한다")
    void rejectsInvalidGenerationGap(String kind, int gap) throws Exception {
        Source source = source(borin, "profile.family", "아리아의 후손", true);
        var body = request(borin, aria, kind, source).put("generationGap", gap);
        postJson("/relationship-candidates", body).andExpect(status().is4xxClientError());
        assertThat(candidates()).isEmpty();
    }

    @Test
    @DisplayName("세대 수를 가진 수동 후보와 수정 요청은 필드를 보존하고 확정 시 역방향을 정규화한다")
    void manualAncestryPreservesGapThroughUpdateAndConfirmation() throws Exception {
        Source source = source(borin, "profile.family", "아리아의 5대손", true);
        var body = request(borin, aria, "DESCENDANT_OF", source).put("generationGap", 5);
        var candidate = data(postJson("/relationship-candidates", body).andExpect(status().isOk()));
        assertThat(candidate.path("generationGap").asInt()).isEqualTo(5);
        var update = body.deepCopy().put("expectedVersion", 0).put("generationGap", 7);
        candidate = data(mvc.perform(put(base() + candidatePath(candidate)).header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(update))).andExpect(status().isOk()));
        assertThat(candidate.path("generationGap").asInt()).isEqualTo(7);
        data(postJson(candidatePath(candidate) + "/confirm", revision(candidate.path("version").asLong())).andExpect(status().isOk()));
        assertThat(only(relations()).path("generationGap").asInt()).isEqualTo(7);
        assertThat(only(relations()).path("subjectCharacterId").asText()).isEqualTo(aria.toString());
    }

    @Test
    @DisplayName("현재 부모 두 세대 경로와 5대손 명제가 충돌하면 자동 관계를 만들지 않는다")
    void automaticGenerationConflictIncludesKnownParentPath() throws Exception {
        source(aria, "profile.family", "자녀: 보린", true);
        source(borin, "profile.family", "자녀: 셀린", true);
        source(celine, "profile.family", "아리아의 5대손", true);
        processCurrentInput();
        assertThat(relations()).isEmpty();
        assertThat(candidates()).hasSize(3).allSatisfy(candidate ->
                assertThat(candidate.path("reviewReasons").toString()).contains("GENERATION_GAP_CONFLICT"));
    }

    @Test
    @DisplayName("이미 확정한 부모 경로와 다른 세대 수는 수동 확정에서도 차단한다")
    void manualConfirmationRejectsGenerationMismatchWithKnownParents() throws Exception {
        var ab = source(aria, "profile.family", "자녀: 보린", true);
        var bc = source(borin, "profile.family", "자녀: 셀린", true);
        var ancestry = source(celine, "profile.특징", "검토할 조상 관계", true);
        for (var body : List.of(request(aria, borin, "PARENT_OF", ab), request(borin, celine, "PARENT_OF", bc))) {
            var candidate = data(postJson("/relationship-candidates", body).andExpect(status().isOk()));
            postJson(candidatePath(candidate) + "/confirm", revision(0)).andExpect(status().isOk());
        }
        var body = request(celine, aria, "DESCENDANT_OF", ancestry).put("generationGap", 5);
        var candidate = data(postJson("/relationship-candidates", body).andExpect(status().isOk()));
        String error = postJson(candidatePath(candidate) + "/confirm", revision(0)).andExpect(status().is4xxClientError())
                .andReturn().getResponse().getContentAsString();
        assertThat(error).contains("GENERATION_GAP_CONFLICT");
        assertThat(relations()).hasSize(2);
    }

    @Test
    @DisplayName("기존 부모 요청을 잘못된 세대 수와 재전송하면 같은 요청으로 승인하지 않는다")
    void invalidGapCannotReplayExistingParentRequest() throws Exception {
        var source = source(borin, "profile.family", "아리아의 아들", true);
        var body = request(borin, aria, "CHILD_OF", source);
        postJson("/relationship-candidates", body).andExpect(status().isOk());
        body.put("generationGap", 5);
        postJson("/relationship-candidates", body).andExpect(status().is4xxClientError());
        assertThat(candidates()).hasSize(1);
    }

    @Test
    @DisplayName("명시적 배우자는 현재 설정 저장과 분리해 자동 확정하며 반복 실행해도 중복되지 않는다")
    void explicitSpouseAutomaticallyConfirmsOnce() throws Exception {
        source(aria, "profile.family", "배우자: 보린", true);
        assertThat(relations()).isEmpty();
        assertThat(getData("/relationship-processing").path("state").asText()).isEqualTo("PENDING");

        processCurrentInput();

        JsonNode relationship = only(relations());
        assertThat(relationship.path("kind").asText()).isEqualTo("SPOUSE");
        assertThat(relationship.path("validity").asText()).isEqualTo("VALID");
        assertThat(relationship.path("evidence").get(0).path("hasEvidence").asBoolean()).isFalse();
        JsonNode candidate = only(candidates());
        assertThat(candidate.path("origin").asText()).isEqualTo("CURRENT_SETTING_RULE");
        assertThat(candidate.path("automaticallyConfirmed").asBoolean()).isTrue();
        assertThat(getData("/relationship-processing").path("autoConfirmedCount").asInt()).isEqualTo(1);

        processCurrentInput();
        processing.requestProcessing(memberId, workId);
        processCurrentInput();
        assertThat(only(relations()).path("id")).isEqualTo(relationship.path("id"));
        assertThat(candidates()).hasSize(1);
        assertThat(getData("/relationship-processing").path("autoConfirmedCount").asInt()).isZero();
    }

    @Test
    @DisplayName("부모와 자녀의 양쪽 명시적 표현은 하나의 관계에 근거 두 개로 합쳐진다")
    void inverseParentStatementsDeduplicate() throws Exception {
        source(aria, "profile.family", "자녀: 보린", true);
        source(borin, "profile.family", "부모: 아리아", true);
        processCurrentInput();

        JsonNode relationship = only(relations());
        assertThat(relationship.path("subjectCharacterId").asText()).isEqualTo(aria.toString());
        assertThat(relationship.path("objectCharacterId").asText()).isEqualTo(borin.toString());
        assertThat(relationship.path("kind").asText()).isEqualTo("PARENT_OF");
        assertThat(relationship.path("evidence")).hasSize(2);
        assertThat(candidates()).hasSize(2);
    }

    @Test
    @DisplayName("형제 관계만 명시되어 있으면 부모를 추정해 생성하지 않는다")
    void siblingsDoNotInventParents() throws Exception {
        source(aria, "profile.family", "형제: 보린", true);
        processCurrentInput();
        assertThat(only(relations()).path("kind").asText()).isEqualTo("SIBLING");
        assertThat(jdbc.queryForObject("select count(*) from characters where work_id = ?", Long.class, workId))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("같은 자녀의 친부모와 양부모는 서로 충돌시키지 않고 구분해 보존한다")
    void biologicalAndAdoptiveParentsCoexist() throws Exception {
        source(borin, "profile.family", "친아버지: 아리아; 양어머니: 셀린", true);
        processCurrentInput();
        assertThat(relations()).hasSize(2);
        assertThat(relations().stream().map(row -> row.path("subtype").asText())).containsExactlyInAnyOrder("BIOLOGICAL", "ADOPTIVE");
        assertThat(relations()).allSatisfy(row -> assertThat(row.path("validity").asText()).isEqualTo("VALID"));
    }

    @Test
    @DisplayName("동명이인과 자기 자신을 지칭하는 관계는 임의 연결하지 않고 후보로 남긴다")
    void duplicateNamesAndSelfReferencesRemainPending() throws Exception {
        tx.executeWithoutResult(transaction -> character(works.getOwnedWorkForUpdate(workId, memberId), "보린"));
        source(aria, "profile.family", "배우자: 보린; 형제: 아리아", true);
        processCurrentInput();
        assertThat(relations()).isEmpty();
        assertThat(candidates()).hasSize(2).allSatisfy(row -> {
            assertThat(row.path("status").asText()).isEqualTo("PENDING");
            assertThat(row.path("objectCharacterId").isNull()).isTrue();
        });
        assertThat(candidates().stream().flatMap(row -> strings(row.path("reviewReasons")).stream()))
                .contains("AMBIGUOUS_TARGET", "SELF_REFERENCE");
    }

    @Test
    @DisplayName("같은 관계의 단정과 명시적 부정이 함께 있으면 어느 쪽도 자동 확정하지 않는다")
    void simultaneousContradictionPreventsAutomaticConfirmation() throws Exception {
        source(aria, "profile.family", "보린의 부모", true);
        source(borin, "profile.family", "아리아의 자녀가 아니다", true);
        processCurrentInput();
        assertThat(relations()).isEmpty();
        assertThat(candidates()).hasSize(2).allSatisfy(row ->
                assertThat(strings(row.path("reviewReasons"))).contains("CONFLICTING"));
    }

    @Test
    @DisplayName("나중에 부정 근거가 저장되면 대기 중부터 기존 자동 관계를 보류하고 재처리 후에도 보류한다")
    void laterContradictionInvalidatesExistingAutomaticSupport() throws Exception {
        source(aria, "profile.family", "보린의 부모", true);
        processCurrentInput();
        String relationId = only(relations()).path("id").asText();
        source(borin, "profile.family", "아리아의 자녀가 아니다", true);
        assertThat(getData("/relationships/" + relationId).path("validity").asText()).isEqualTo("REVIEW_REQUIRED");

        processCurrentInput();

        assertThat(getData("/relationships/" + relationId).path("validity").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(candidates()).hasSize(2);
        assertThat(candidates().stream().filter(row -> row.path("automaticallyConfirmed").asBoolean()).toList())
                .singleElement().satisfies(row -> assertThat(strings(row.path("reviewReasons"))).contains("CONFLICTING"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("상충하는 새 명제나 기존 자동 관계는 정상 부모 관계의 순환 경로로 쓰지 않는다")
    void conflictingClaimDoesNotCreatePhantomAncestryCycle(boolean previouslyConfirmed) throws Exception {
        source(aria, "profile.family", "보린의 부모", true);
        String previousRelationId = null;
        if (previouslyConfirmed) {
            processCurrentInput();
            previousRelationId = only(relations()).path("id").asText();
        }
        source(borin, "profile.family", "아리아의 자녀가 아니다; 자녀: 아리아", true);

        processCurrentInput();

        List<JsonNode> active = relations().stream().filter(row -> row.path("validity").asText().equals("VALID")).toList();
        assertThat(active).singleElement().satisfies(row -> {
            assertThat(row.path("subjectCharacterId").asText()).isEqualTo(borin.toString());
            assertThat(row.path("objectCharacterId").asText()).isEqualTo(aria.toString());
        });
        assertThat(candidates()).hasSize(3);
        assertThat(candidates().stream().filter(row -> row.path("subjectCharacterId").asText().equals(aria.toString())
                || row.path("assertion").asText().equals("NEGATED")).toList())
                .hasSize(2).allSatisfy(row -> assertThat(strings(row.path("reviewReasons"))).contains("CONFLICTING"));
        if (previousRelationId != null) {
            assertThat(getData("/relationships/" + previousRelationId).path("validity").asText()).isEqualTo("REVIEW_REQUIRED");
        }

        processing.requestProcessing(memberId, workId);
        processCurrentInput();
        assertThat(relations().stream().filter(row -> row.path("validity").asText().equals("VALID")).toList())
                .singleElement().satisfies(row -> assertThat(row.path("id")).isEqualTo(active.getFirst().path("id")));
        assertThat(candidates()).hasSize(3);
    }

    @Test
    @DisplayName("이름 해석 대상이 바뀐 기존 자동 관계는 새 대상의 정상 부모 관계를 순환으로 보류하지 않는다")
    void changedTargetDoesNotCreatePhantomAncestryCycle() throws Exception {
        source(aria, "profile.family", "자녀: 보린", true);
        processCurrentInput();
        String previousRelationId = only(relations()).path("id").asText();
        characterService.updateCharacter(memberId, workId, borin,
                new CharacterUpdateRequest("다린", null, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of()));
        characterService.updateCharacter(memberId, workId, celine,
                new CharacterUpdateRequest("보린", null, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of()));
        source(celine, "profile.family", "자녀: 아리아", true);

        processCurrentInput();

        assertThat(getData("/relationships/" + previousRelationId).path("validity").asText()).isEqualTo("REVIEW_REQUIRED");
        JsonNode previousCandidate = candidates().stream()
                .filter(row -> row.path("subjectCharacterId").asText().equals(aria.toString())).findFirst().orElseThrow();
        assertThat(previousCandidate.path("objectCharacterId").asText()).isEqualTo(borin.toString());
        assertThat(strings(previousCandidate.path("reviewReasons"))).contains("TARGET_CHANGED");
        List<JsonNode> active = relations().stream().filter(row -> row.path("validity").asText().equals("VALID")).toList();
        assertThat(active).singleElement().satisfies(row -> {
            assertThat(row.path("subjectCharacterId").asText()).isEqualTo(celine.toString());
            assertThat(row.path("objectCharacterId").asText()).isEqualTo(aria.toString());
        });

        processing.requestProcessing(memberId, workId);
        processCurrentInput();
        assertThat(relations().stream().filter(row -> row.path("validity").asText().equals("VALID")).toList())
                .singleElement().satisfies(row -> assertThat(row.path("id")).isEqualTo(active.getFirst().path("id")));
        assertThat(candidates()).hasSize(2);
    }

    @Test
    @DisplayName("상충한 자동 근거와 별개의 수동 근거가 있으면 실제 부모 순환을 계속 차단한다")
    void independentManualSupportStillPreventsAncestryCycle() throws Exception {
        Source source = source(aria, "profile.family", "보린의 부모", true);
        processCurrentInput();
        String relationId = only(relations()).path("id").asText();
        JsonNode manual = data(postJson("/relationship-candidates", request(aria, borin, "PARENT_OF", source))
                .andExpect(status().is2xxSuccessful()));
        postJson(candidatePath(manual) + "/confirm", revision(0)).andExpect(status().isOk());
        source(borin, "profile.family", "아리아의 자녀가 아니다; 자녀: 아리아", true);

        processCurrentInput();

        assertThat(only(relations()).path("id").asText()).isEqualTo(relationId);
        assertThat(only(relations()).path("validity").asText()).isEqualTo("VALID");
        assertThat(candidates().stream().filter(row -> row.path("automaticallyConfirmed").asBoolean()).toList())
                .singleElement().satisfies(row -> assertThat(strings(row.path("reviewReasons"))).contains("CONFLICTING"));
        assertThat(candidates().stream().filter(row -> row.path("subjectCharacterId").asText().equals(borin.toString())
                && row.path("assertion").asText().equals("ASSERTED")).toList())
                .singleElement().satisfies(row -> {
                    assertThat(row.path("status").asText()).isEqualTo("PENDING");
                    assertThat(strings(row.path("reviewReasons"))).contains("ANCESTRY_CYCLE");
                });
        assertThat(getData(candidatePath(manual)).path("automaticallyConfirmed").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("한 처리 묶음의 조상 순환은 입력 순서와 무관하게 모든 간선을 보류한다")
    void ancestryCycleHoldsAllNewEdges() throws Exception {
        source(aria, "profile.family", "자녀: 보린", true);
        source(borin, "profile.family", "자녀: 셀린", true);
        source(celine, "profile.family", "자녀: 아리아", true);
        processCurrentInput();
        assertThat(relations()).isEmpty();
        assertThat(candidates()).hasSize(3).allSatisfy(row ->
                assertThat(strings(row.path("reviewReasons"))).contains("ANCESTRY_CYCLE"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("철회한 부모 관계는 동일 또는 갱신 출처의 재처리에서도 새 관계의 순환 경로로 쓰지 않는다")
    void withdrawnAutomaticRelationDoesNotCreatePhantomAncestryCycle(boolean replaceOriginalSource) throws Exception {
        source(aria, "profile.family", "자녀: 보린", true);
        processCurrentInput();
        JsonNode withdrawn = only(relations());
        postJson("/relationships/" + withdrawn.path("id").asText() + "/withdraw",
                revision(withdrawn.path("version").asLong())).andExpect(status().isOk());
        if (replaceOriginalSource) {
            source(aria, "profile.family", "자녀: 보린", true);
        }
        source(borin, "profile.family", "자녀: 아리아", true);

        processCurrentInput();

        assertThat(getData("/relationships/" + withdrawn.path("id").asText()).path("validity").asText()).isEqualTo("WITHDRAWN");
        List<JsonNode> active = relations().stream().filter(row -> row.path("validity").asText().equals("VALID")).toList();
        assertThat(active).singleElement().satisfies(row -> {
            assertThat(row.path("subjectCharacterId").asText()).isEqualTo(borin.toString());
            assertThat(row.path("objectCharacterId").asText()).isEqualTo(aria.toString());
        });
        if (replaceOriginalSource) {
            assertThat(candidates().stream().filter(row -> row.path("status").asText().equals("PENDING")).toList())
                    .singleElement().satisfies(row -> assertThat(strings(row.path("reviewReasons"))).contains("PREVIOUSLY_WITHDRAWN"));
        }
    }

    @Test
    @DisplayName("사용자가 제외하거나 수정한 후보는 같은 입력 재처리로 되살리거나 덮어쓰지 않는다")
    void dismissedAndEditedCandidatesRemainUserDecisions() throws Exception {
        Source spouse = source(aria, "profile.family", "배우자: 미등록인", true);
        source(borin, "profile.family", "형제: 다른미등록인", true);
        processCurrentInput();
        JsonNode edited = candidates().stream().filter(row -> row.path("subjectCharacterId").asText().equals(aria.toString())).findFirst().orElseThrow();
        JsonNode dismissed = candidates().stream().filter(row -> row.path("subjectCharacterId").asText().equals(borin.toString())).findFirst().orElseThrow();
        postJson(candidatePath(dismissed) + "/dismiss", revision(0)).andExpect(status().isOk());
        ObjectNode update = request(aria, celine, "SPOUSE", spouse);
        update.remove("requestId");
        update.put("expectedVersion", 0);
        putJson(candidatePath(edited), update).andExpect(status().isOk());

        processing.requestProcessing(memberId, workId);
        processCurrentInput();

        assertThat(candidates()).hasSize(2);
        assertThat(getData(candidatePath(dismissed)).path("status").asText()).isEqualTo("DISMISSED");
        assertThat(getData(candidatePath(edited)).path("objectCharacterId").asText()).isEqualTo(celine.toString());
        assertThat(getData(candidatePath(edited)).path("status").asText()).isEqualTo("PENDING");
        assertThat(relations()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"pending, SIBLING", "dismissed, SIBLING", "edited, SIBLING", "confirmed, SIBLING", "withdrawn, SIBLING",
            "pending, CHILD_OF", "dismissed, CHILD_OF", "edited, CHILD_OF", "confirmed, CHILD_OF", "withdrawn, CHILD_OF"})
    @DisplayName("형제 호칭 또는 혼합 슬롯의 서수 지원 개선은 구 후보의 대기·제외·수정·확정·철회를 유지한다")
    void assertionUpgradePreservesLegacyDecisions(String decision, RelationshipKind kind) throws Exception {
        boolean mixedSlot = kind == RelationshipKind.CHILD_OF;
        String value = mixedSlot ? "보린의 아들; 셀린의 넷째 아들" : "보린의 언니";
        Source source = source(aria, "profile.family", value, true);
        assertThat(familyParser.parse("profile.family", json.createObjectNode().put("factValue", value))
                .relationships()).hasSize(mixedSlot ? 2 : 1).allSatisfy(claim ->
                        assertThat(claim.assertion()).isEqualTo(RelationshipAssertion.ASSERTED));
        JsonNode legacy = legacyUncertainClaim(source, borin, "보린", kind);
        if (decision.equals("dismissed")) {
            postJson(candidatePath(legacy) + "/dismiss", revision(0)).andExpect(status().isOk());
        } else if (!decision.equals("pending")) {
            ObjectNode update = request(aria, decision.equals("edited") ? celine : borin,
                    decision.equals("edited") ? "SPOUSE" : kind.name(), source);
            update.remove("requestId");
            update.put("expectedVersion", 0);
            putJson(candidatePath(legacy), update).andExpect(status().isOk());
            if (!decision.equals("edited")) {
                JsonNode confirmed = data(postJson(candidatePath(legacy) + "/confirm", revision(1))
                        .andExpect(status().isOk()));
                if (decision.equals("withdrawn")) {
                    String relationshipPath = "/relationships/" + confirmed.path("confirmedRelationshipId").asText();
                    postJson(relationshipPath + "/withdraw", revision(getData(relationshipPath).path("version").asLong()))
                            .andExpect(status().isOk());
                }
            }
        }
        JsonNode before = getData(candidatePath(legacy));

        processCurrentInput();
        processing.requestProcessing(memberId, workId);
        processCurrentInput();

        assertThat(candidates()).hasSize(mixedSlot ? 2 : 1);
        JsonNode after = getData(candidatePath(legacy));
        assertThat(after.path("id")).isEqualTo(before.path("id"));
        assertThat(after.path("version")).isEqualTo(before.path("version"));
        assertThat(after.path("status")).isEqualTo(before.path("status"));
        assertThat(after.path("objectCharacterId")).isEqualTo(before.path("objectCharacterId"));
        assertThat(after.path("kind")).isEqualTo(before.path("kind"));
        assertThat(after.path("assertion")).isEqualTo(before.path("assertion"));
        assertThat(after.path("automaticallyConfirmed").asBoolean()).isFalse();
        if (decision.equals("confirmed") || decision.equals("withdrawn")) {
            assertThat(relations()).hasSize(mixedSlot ? 2 : 1);
            assertThat(getData("/relationships/" + before.path("confirmedRelationshipId").asText()).path("validity").asText())
                    .isEqualTo(decision.equals("withdrawn") ? "WITHDRAWN" : "VALID");
        } else {
            assertThat(relations()).hasSize(mixedSlot ? 1 : 0);
        }
        if (mixedSlot) {
            assertThat(relations().stream().filter(row -> row.path("subjectCharacterId").asText().equals(celine.toString())))
                    .singleElement().satisfies(relation -> {
                        assertThat(relation.path("objectCharacterId").asText()).isEqualTo(aria.toString());
                        assertThat(relation.path("validity").asText()).isEqualTo("VALID");
                    });
        }
    }

    @Test
    @DisplayName("구 규칙 제외 후보의 같은 출처 다른 명제와 독립된 새 출처는 생성을 막지 않는다")
    void legacyDecisionOnlyMatchesItsOriginalClaimAndExactSource() throws Exception {
        Source first = source(aria, "profile.family", "보린의 언니; 셀린의 동생", true);
        JsonNode dismissed = legacyUncertainClaim(first, borin, "보린", RelationshipKind.SIBLING);
        postJson(candidatePath(dismissed) + "/dismiss", revision(0)).andExpect(status().isOk());
        source(aria, "profile.other_family", "보린의 언니", true);

        processCurrentInput();

        assertThat(candidates()).hasSize(3);
        assertThat(getData(candidatePath(dismissed)).path("status").asText()).isEqualTo("DISMISSED");
        assertThat(relations()).hasSize(2).allSatisfy(relation -> {
            assertThat(relation.path("kind").asText()).isEqualTo("SIBLING");
            assertThat(relation.path("validity").asText()).isEqualTo("VALID");
            assertThat(relation.path("evidence")).hasSize(1);
        });
    }

    @Test
    @DisplayName("서수 자녀와 설명이 붙은 언니는 미등록 부모·등록 형제를 가계도에 표시한다")
    void observedOrdinalChildrenAndSiblingDescriptionReachFamilyTree() throws Exception {
        UUID[] characters = tx.execute(transaction -> {
            Work work = works.getOwnedWorkForUpdate(workId, memberId);
            return new UUID[]{character(work, "다온"), character(work, "시온"),
                    character(work, "하린"), character(work, "유나")};
        });
        source(characters[0], "profile.가족관계", "마렌의 넷째 아들", true);
        source(characters[1], "profile.가족", "로엔의 두 번째 딸", true);
        source(characters[2], "profile.특징", "유나의 언니인 6등급 탐험가", true);
        source(aria, "profile.가족관계", "보린의 셋째 아들", false);
        source(borin, "profile.특징", "셀린의 언니인 6등급 탐험가", false);

        processCurrentInput();

        assertThat(candidates()).hasSize(3);
        assertThat(only(relations()).path("kind").asText()).isEqualTo("SIBLING");
        JsonNode tree = getData("/family-tree");
        assertThat(tree.path("edges")).hasSize(3);
        assertThat(tree.path("nodes")).hasSize(6);
        assertThat(tree.path("optionalLinkCount").asInt()).isEqualTo(2);
        assertThat(rows(tree.path("nodes")).stream().map(node -> node.path("name").asText()))
                .containsExactlyInAnyOrder("마렌", "다온", "로엔", "시온", "하린", "유나");
        assertThat(rows(tree.path("edges")).stream().filter(edge -> edge.path("kind").asText().equals("PARENT_OF")))
                .hasSize(2).allSatisfy(edge -> assertThat(edge.path("subjectRole").asText()).isEqualTo("PARENT"));
        assertThat(rows(tree.path("edges")).stream().map(edge -> edge.path("objectRole").asText()))
                .contains("SON", "DAUGHTER");

        processing.requestProcessing(memberId, workId);
        processCurrentInput();
        assertThat(candidates()).hasSize(3);
        assertThat(getData("/family-tree").path("edges")).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("설명형 상대의 아들은 같은 이름의 프로필 유무와 무관하게 검토에 남고 가계도에 표시되지 않는다")
    void descriptiveParentRemainsUncertainRegardlessOfRegisteredTarget(boolean registeredTarget) throws Exception {
        String description = "청암 클랜 수인 남성";
        UUID targetId = registeredTarget ? tx.execute(transaction ->
                character(works.getOwnedWorkForUpdate(workId, memberId), description)) : null;
        source(aria, "profile.가족관계", description + "의 아들", true);

        processCurrentInput();

        JsonNode candidate = only(candidates());
        assertThat(candidate.path("objectMention").asText()).isEqualTo(description);
        if (registeredTarget) {
            assertThat(candidate.path("objectCharacterId").asText()).isEqualTo(targetId.toString());
        } else {
            assertThat(candidate.path("objectCharacterId").isNull()).isTrue();
        }
        assertThat(candidate.path("kind").asText()).isEqualTo("CHILD_OF");
        assertThat(candidate.path("assertion").asText()).isEqualTo("UNCERTAIN");
        assertThat(candidate.path("status").asText()).isEqualTo("PENDING");
        assertThat(candidate.path("automaticallyConfirmed").asBoolean()).isFalse();
        assertThat(candidate.path("displayableAsMention").asBoolean()).isFalse();
        assertThat(strings(candidate.path("reviewReasons"))).contains("UNCERTAIN");
        assertThat(relations()).isEmpty();
        JsonNode tree = getData("/family-tree");
        assertThat(tree.path("nodes")).isEmpty();
        assertThat(tree.path("edges")).isEmpty();
        assertThat(tree.path("pendingCandidateCount").asInt()).isEqualTo(1);
        assertThat(tree.path("optionalLinkCount").asInt()).isZero();

        processing.requestProcessing(memberId, workId);
        processCurrentInput();

        JsonNode reprocessed = only(candidates());
        assertThat(reprocessed.path("id")).isEqualTo(candidate.path("id"));
        assertThat(reprocessed.path("assertion").asText()).isEqualTo("UNCERTAIN");
        assertThat(reprocessed.path("status").asText()).isEqualTo("PENDING");
        assertThat(relations()).isEmpty();
        assertThat(getData("/family-tree").path("edges")).isEmpty();
    }

    @Test
    @DisplayName("현재 슬롯 교체는 과거 자동 관계를 보류하고 새 관계와 Fact 이력을 함께 남긴다")
    void changedCurrentSlotReplacesSupportWithoutErasingHistory() throws Exception {
        Source before = source(aria, "profile.family", "배우자: 보린", true);
        processCurrentInput();
        String oldId = only(relations()).path("id").asText();
        Source after = source(aria, "profile.family", "배우자: 셀린", true);
        processCurrentInput();

        assertThat(relations()).hasSize(2);
        JsonNode old = getData("/relationships/" + oldId);
        assertThat(old.path("validity").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(old.path("evidence").get(0).path("current").asBoolean()).isFalse();
        assertThat(relations().stream().filter(row -> row.path("validity").asText().equals("VALID")).toList())
                .singleElement().satisfies(row -> assertThat(row.path("evidence").get(0).path("sourceFactId").asText())
                        .isEqualTo(after.factId().toString()));
        assertThat(jdbc.queryForObject("select count(*) from character_facts where id in (?, ?)", Long.class,
                before.factId(), after.factId())).isEqualTo(2);
    }

    @Test
    @DisplayName("과거 Fact는 처리하지 않으며 읽지 못한 현재 가족 표현은 누락 대신 별도 집계한다")
    void historyOnlyFactsAreSkippedAndUnsupportedFamilySlotsCounted() throws Exception {
        source(aria, "profile.old_family", "배우자: 보린", false);
        source(aria, "profile.family", "복잡한 가족 사정 때문에 서로를 피한다", true);
        source(borin, "profile.job", "검사", true);
        processCurrentInput();
        assertThat(candidates()).isEmpty();
        assertThat(relations()).isEmpty();
        assertThat(getData("/relationship-processing").path("unsupportedSourceCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("처리 상태 조회와 요청은 다른 회원 작품이나 미인증 요청을 허용하지 않는다")
    void processingEndpointsEnforceOwnership() throws Exception {
        String foreignPath = "/api/v1/works/" + foreignWorkId + "/relationship-processing";
        mvc.perform(get(foreignPath).header(HttpHeaders.AUTHORIZATION, bearer())).andExpect(status().isNotFound());
        mvc.perform(post(foreignPath).header(HttpHeaders.AUTHORIZATION, bearer())).andExpect(status().isNotFound());
        mvc.perform(get(base() + "/relationship-processing")).andExpect(status().isUnauthorized());
        mvc.perform(post(base() + "/relationship-processing")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("수동 요청은 같은 대기를 합치고 실패는 원설정을 유지하며 새 입력은 재시도 지연을 건너뛴다")
    void durableQueueCoalescesRequestsAndRetriesWithoutLosingCurrentSettings() throws Exception {
        assertThat(getData("/relationship-processing").path("state").asText()).isEqualTo("NEVER_PROCESSED");
        assertThat(ready()).doesNotContain(workId);
        JsonNode first = data(mvc.perform(post(base() + "/relationship-processing").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk()));
        JsonNode again = data(mvc.perform(post(base() + "/relationship-processing").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk()));
        assertThat(again.path("inputVersion")).isEqualTo(first.path("inputVersion"));
        assertThat(ready()).contains(workId);
        processing.recordFailure(workId, currentInputVersion());
        assertThat(getData("/relationship-processing").path("state").asText()).isEqualTo("FAILED");
        assertThat(ready()).doesNotContain(workId);
        processCurrentInput();
        assertThat(getData("/relationship-processing").path("processedVersion").asLong()).isZero();

        source(aria, "profile.family", "배우자: 보린", true);
        assertThat(ready()).contains(workId);
        assertThat(getData("/relationship-processing").path("state").asText()).isEqualTo("PENDING");
        processCurrentInput();
        JsonNode complete = getData("/relationship-processing");
        assertThat(complete.path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(complete.path("processedVersion")).isEqualTo(complete.path("inputVersion"));
        assertThat(complete.path("failureCode").isNull()).isTrue();
        assertThat(complete.path("autoConfirmedCount").asInt()).isEqualTo(1);
        assertThat(only(relations()).path("validity").asText()).isEqualTo("VALID");
        assertThat(ready()).doesNotContain(workId);

        processing.requestProcessing(memberId, workId);
        processing.recordFailure(workId, currentInputVersion());
        processing.requestProcessing(memberId, workId);
        assertThat(ready()).contains(workId);
        processCurrentInput();
        assertThat(getData("/relationship-processing").path("state").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("후처리가 관계를 쓴 뒤 실패해도 원설정 커밋은 유지하고 후처리만 롤백해 안전하게 재시도한다")
    void failureAfterRelationshipWritesRollsBackOnlyProcessingTransaction() throws Exception {
        Source original = source(aria, "profile.family", "배우자: 보린", true);
        long inputVersion = getData("/relationship-processing").path("inputVersion").asLong();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("synthetic post-write failure");
        }).when(automationProcessor).process(any(Work.class));
        try {
            assertThatThrownBy(() -> processing.process(workId, inputVersion)).isInstanceOf(IllegalStateException.class);
            assertThat(candidates()).isEmpty();
            assertThat(relations()).isEmpty();
            assertThat(states.findByWorkId(workId)).isEmpty();

            // 다른 테스트 작품을 처리하지 않도록 scheduler의 대상 선택만 좁힌다.
            RelationshipProcessingStateRepository queue = mock(RelationshipProcessingStateRepository.class);
            when(queue.findReadyInputs(any(), any())).thenReturn(List.of(new RelationshipProcessingInput(workId, inputVersion)));
            new RelationshipProcessingScheduler(queue, processing).processPending();

            JsonNode failed = getData("/relationship-processing");
            assertThat(failed.path("state").asText()).isEqualTo("FAILED");
            assertThat(failed.path("failureCode").asText()).isEqualTo("RELATIONSHIP_PROCESSING_FAILED");
            assertThat(failed.toString()).doesNotContain("synthetic post-write failure");
            assertThat(failed.path("inputVersion").asLong()).isEqualTo(inputVersion);
            assertThat(failed.path("processedVersion").asLong()).isZero();
            assertThat(candidates()).isEmpty();
            assertThat(relations()).isEmpty();
            assertThat(getData("/relationship-sources?characterId=" + aria).get(0).path("factId").asText())
                    .isEqualTo(original.factId().toString());
            assertThat(ready()).doesNotContain(workId);
        } finally {
            doCallRealMethod().when(automationProcessor).process(any(Work.class));
        }

        processing.requestProcessing(memberId, workId);
        processCurrentInput();

        assertThat(getData("/relationship-processing").path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(only(relations()).path("validity").asText()).isEqualTo("VALID");
        assertThat(candidates()).hasSize(1);
    }

    @Test
    @DisplayName("실패 롤백 뒤 새 설정이 커밋되면 늦은 실패 기록은 새 입력을 실패로 표시하거나 지연하지 않는다")
    void lateFailureAfterRollbackDoesNotDelayNewlyCommittedInput() throws Exception {
        source(aria, "profile.family", "배우자: 보린", true);
        RelationshipProcessingInput attempted = readyInput();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("synthetic post-write failure");
        }).when(automationProcessor).process(any(Work.class));
        try {
            assertThatThrownBy(() -> processing.process(attempted.workId(), attempted.inputVersion()))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            doCallRealMethod().when(automationProcessor).process(any(Work.class));
        }
        assertThat(candidates()).isEmpty();
        assertThat(relations()).isEmpty();

        Source current = source(aria, "profile.family", "배우자: 셀린", true);
        RelationshipProcessingInput latest = readyInput();
        assertThat(latest.inputVersion()).isGreaterThan(attempted.inputVersion());

        processing.recordFailure(attempted.workId(), attempted.inputVersion());

        JsonNode pending = getData("/relationship-processing");
        assertThat(pending.path("state").asText()).isEqualTo("PENDING");
        assertThat(pending.path("failureCode").isNull()).isTrue();
        assertThat(pending.path("retryAt").isNull()).isTrue();
        assertThat(pending.path("inputVersion").asLong()).isEqualTo(latest.inputVersion());
        assertThat(pending.path("processedVersion").asLong()).isZero();
        assertThat(states.findByWorkId(workId)).isEmpty();
        assertThat(readyInput()).isEqualTo(latest);
        assertThat(getData("/relationship-sources?characterId=" + aria).get(0).path("factId").asText())
                .isEqualTo(current.factId().toString());

        processing.process(latest.workId(), latest.inputVersion());

        assertThat(only(relations()).path("validity").asText()).isEqualTo("VALID");
        assertThat(only(candidates()).path("objectCharacterId").asText()).isEqualTo(celine.toString());
        assertThat(getData("/relationship-processing").path("state").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("큐에서 읽은 뒤 설정이 바뀌면 오래된 큐 항목은 새 입력 세대를 처리하지 않는다")
    void staleQueuedInputCannotProcessNewerGeneration() throws Exception {
        source(aria, "profile.family", "배우자: 보린", true);
        RelationshipProcessingInput queued = readyInput();
        source(aria, "profile.family", "배우자: 셀린", true);
        RelationshipProcessingInput latest = readyInput();
        assertThat(latest.inputVersion()).isGreaterThan(queued.inputVersion());

        processing.process(queued.workId(), queued.inputVersion());

        assertThat(candidates()).isEmpty();
        assertThat(relations()).isEmpty();
        assertThat(states.findByWorkId(workId)).isEmpty();
        assertThat(readyInput()).isEqualTo(latest);
        assertThat(getData("/relationship-processing").path("state").asText()).isEqualTo("PENDING");

        processing.process(latest.workId(), latest.inputVersion());

        assertThat(only(candidates()).path("objectCharacterId").asText()).isEqualTo(celine.toString());
        assertThat(getData("/relationship-processing").path("processedVersion").asLong()).isEqualTo(latest.inputVersion());
    }

    @Test
    @DisplayName("이전 세대의 늦은 실패는 최신 실패의 재시도 시각이나 이미 완료한 성공을 덮어쓰지 않는다")
    void staleFailureCannotOverwriteNewerFailureOrCompletedSuccess() throws Exception {
        source(aria, "profile.family", "배우자: 보린", true);
        RelationshipProcessingInput previous = readyInput();
        source(aria, "profile.family", "배우자: 셀린", true);
        RelationshipProcessingInput latest = readyInput();
        processing.recordFailure(latest.workId(), latest.inputVersion());
        var latestFailure = processing.getStatus(memberId, workId);
        assertThat(latestFailure.state().name()).isEqualTo("FAILED");
        assertThat(latestFailure.retryAt()).isNotNull();

        processing.recordFailure(previous.workId(), previous.inputVersion());

        assertThat(processing.getStatus(memberId, workId)).isEqualTo(latestFailure);
        assertThat(states.findByWorkId(workId).orElseThrow().getAttemptedVersion()).isEqualTo(latest.inputVersion());
        assertThat(ready()).doesNotContain(workId);

        processing.requestProcessing(memberId, workId);
        processing.process(latest.workId(), latest.inputVersion());
        var completed = processing.getStatus(memberId, workId);
        assertThat(completed.state().name()).isEqualTo("SUCCEEDED");
        assertThat(completed.processedVersion()).isEqualTo(latest.inputVersion());

        processing.recordFailure(previous.workId(), previous.inputVersion());
        processing.recordFailure(latest.workId(), latest.inputVersion());

        assertThat(processing.getStatus(memberId, workId)).isEqualTo(completed);
        assertThat(ready()).doesNotContain(workId);
        assertThat(only(relations()).path("validity").asText()).isEqualTo("VALID");
    }

    @Test
    @DisplayName("작품 영구 삭제는 새 처리 상태와 관계 원장을 함께 정리한다")
    void workPurgeDeletesProcessingState() throws Exception {
        source(aria, "profile.family", "배우자: 보린", true);
        source(borin, "profile.ancestor", "태조의 5대손", true);
        source(celine, "profile.ancestor", "태조의 후손", true);
        processCurrentInput();
        var body = json.createObjectNode().put("requestId", UUID.randomUUID().toString()).put("name", "태조");
        var members = body.putArray("candidates");
        candidates().stream().filter(candidate -> candidate.path("objectCharacterId").isNull())
                .forEach(candidate -> members.addObject().put("candidateId", candidate.path("id").asText())
                        .put("expectedVersion", candidate.path("version").asLong()));
        postJson("/relationship-mention-groups", body).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from relationship_mention_groups where work_id = ?", Long.class, workId)).isEqualTo(1);
        long inputVersion = currentInputVersion();
        assertThat(states.findByWorkId(workId)).isPresent();
        jdbc.execute("create table if not exists episode_chunks (id uuid primary key, episode_id uuid not null)");
        tx.executeWithoutResult(transaction -> purge.purgeWorkData(workId));
        assertThat(states.findByWorkId(workId)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from relationship_candidates where work_id = ?", Long.class, workId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from relationship_mention_groups where work_id = ?", Long.class, workId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from works where id = ?", Long.class, foreignWorkId)).isEqualTo(1);
        processing.process(workId, inputVersion);
        processing.recordFailure(workId, inputVersion);
        assertThat(states.findByWorkId(workId)).isEmpty();
    }

    @Test
    @DisplayName("사용자가 확정한 관계는 관련 없는 처리 입력 세대 변경으로 보류하지 않는다")
    void manualConfirmationSurvivesUnrelatedInputVersionChange() throws Exception {
        Source source = source(aria, "profile.family", "가족 관계를 직접 검토한 설정", true);
        JsonNode candidate = data(postJson("/relationship-candidates", request(aria, borin, "SPOUSE", source))
                .andExpect(status().is2xxSuccessful()));
        JsonNode confirmed = data(postJson(candidatePath(candidate) + "/confirm", revision(0)).andExpect(status().isOk()));
        source(celine, "profile.job", "검사", true);
        assertThat(getData("/relationships/" + confirmed.path("confirmedRelationshipId").asText()).path("validity").asText())
                .isEqualTo("VALID");
    }

    @Test
    @DisplayName("캐릭터 보관과 복원은 자동 관계를 재검증하고 복원된 동일 관계를 재사용한다")
    void archiveAndRestoreRevalidateAutomaticSupport() throws Exception {
        source(aria, "profile.family", "배우자: 보린", true);
        processCurrentInput();
        String id = only(relations()).path("id").asText();
        characterService.archiveCharacter(memberId, workId, borin);
        assertThat(getData("/relationships/" + id).path("validity").asText()).isEqualTo("ARCHIVED_ENDPOINT");
        processCurrentInput();
        characterService.restoreCharacter(memberId, workId, borin);
        assertThat(getData("/relationships/" + id).path("validity").asText()).isEqualTo("REVIEW_REQUIRED");
        processCurrentInput();
        assertThat(only(relations()).path("id").asText()).isEqualTo(id);
        assertThat(only(relations()).path("validity").asText()).isEqualTo("VALID");
        assertThat(candidates()).hasSize(1);
    }

    private UUID character(Work work, String name) {
        WorkCharacter character = WorkCharacter.create(work, name, null, null, null, null, null, null, null, null, null);
        em.persist(character);
        return character.getId();
    }

    private Source source(UUID characterId, String key, String value, boolean current) {
        return tx.execute(transaction -> {
            works.getOwnedWorkForUpdate(workId, memberId);
            WorkCharacter character = em.find(WorkCharacter.class, characterId);
            ObjectNode valueJson = json.createObjectNode().put("value", value);
            CharacterFact fact = CharacterFact.createManual(character, CharacterFactType.PROFILE, key, value, valueJson);
            em.persist(fact);
            if (current) {
                CharacterSnapshotSlot slot = new CharacterSnapshotSlot(CharacterFactType.PROFILE, key);
                var entries = new LinkedHashMap<>(snapshots.read(character));
                entries.put(slot, new CharacterSnapshotEntry(slot, value, valueJson, true));
                snapshots.replace(character, entries, true);
                sourceManager.replaceSources(character, slot, List.of(fact));
            }
            return new Source(fact.getId(), characterId);
        });
    }

    private ObjectNode request(UUID subject, UUID object, String kind, Source source) throws Exception {
        JsonNode context = null;
        for (JsonNode row : getData("/relationship-sources?characterId=" + source.characterId())) {
            if (row.path("factId").asText().equals(source.factId().toString())) context = row;
        }
        assertThat(context).isNotNull();
        ObjectNode body = json.createObjectNode().put("requestId", UUID.randomUUID().toString())
                .put("subjectCharacterId", subject.toString()).put("objectCharacterId", object.toString())
                .put("kind", kind).put("subtype", "UNSPECIFIED").put("temporalScope", "CURRENT").put("assertion", "ASSERTED");
        body.putArray("evidence").addObject().put("sourceFactId", source.factId().toString())
                .put("sourceToken", context.path("contextToken").asText());
        return body;
    }

    private JsonNode legacyUncertainClaim(Source source, UUID objectId, String objectMention, RelationshipKind kind) throws Exception {
        UUID candidateId = tx.execute(transaction -> {
            Work work = works.getOwnedWorkForUpdate(workId, memberId);
            var context = relationshipSources.findCurrentSources(workId).stream()
                    .filter(row -> row.factId().equals(source.factId())).findFirst().orElseThrow();
            var evidence = List.of(new RelationshipEvidenceRequest(source.factId(), context.contextToken()));
            var identity = new RelationshipCandidateCreateRequest(null, source.characterId(), null, objectMention,
                    kind, RelationshipSubtype.UNSPECIFIED, RelationshipTemporalScope.CURRENT,
                    null, RelationshipAssertion.UNCERTAIN, evidence);
            UUID requestId = UUID.nameUUIDFromBytes(("family-rule-v1:" + workId + ":"
                    + relationshipRules.requestHash(identity, null, null)).getBytes(StandardCharsets.UTF_8));
            var original = new RelationshipCandidateCreateRequest(requestId, source.characterId(), objectId, objectMention,
                    identity.kind(), identity.subtype(), identity.temporalScope(), null, identity.assertion(), evidence);
            RelationshipCandidate candidate = RelationshipCandidate.create(work, requestId,
                    relationshipRules.requestHash(original, null, null), em.find(WorkCharacter.class, source.characterId()),
                    em.find(WorkCharacter.class, objectId), objectMention, identity.kind(), identity.subtype(),
                    identity.temporalScope(), null, identity.assertion(), null);
            candidate.markRuleGenerated();
            candidate.holdAutomatically(kind == RelationshipKind.SIBLING ? "UNCERTAIN" : "UNSUPPORTED_FAMILY_SYNTAX");
            em.persist(candidate);
            em.persist(RelationshipEvidence.create(candidate, em.find(CharacterFact.class, source.factId()), context.contextToken()));
            return candidate.getId();
        });
        return getData("/relationship-candidates/" + candidateId);
    }

    private long currentInputVersion() { return processing.getStatus(memberId, workId).inputVersion(); }
    private void processCurrentInput() { processing.process(workId, currentInputVersion()); }
    private List<RelationshipProcessingInput> readyInputs() {
        return states.findReadyInputs(LocalDateTime.now(), PageRequest.of(0, 1000));
    }
    private RelationshipProcessingInput readyInput() {
        return readyInputs().stream().filter(input -> input.workId().equals(workId)).findFirst().orElseThrow();
    }
    private List<UUID> ready() { return readyInputs().stream().map(RelationshipProcessingInput::workId).toList(); }
    private List<JsonNode> candidates() throws Exception { return rows(getData("/relationship-candidates?size=100").path("content")); }
    private List<JsonNode> relations() throws Exception { return rows(getData("/relationships?size=100").path("content")); }
    private List<JsonNode> rows(JsonNode values) {
        List<JsonNode> rows = new ArrayList<>();
        values.forEach(rows::add);
        return rows;
    }
    private List<String> strings(JsonNode values) { return rows(values).stream().map(JsonNode::asText).toList(); }
    private JsonNode only(List<JsonNode> rows) { assertThat(rows).hasSize(1); return rows.getFirst(); }
    private ObjectNode revision(long version) { return json.createObjectNode().put("expectedVersion", version); }
    private String candidatePath(JsonNode candidate) { return "/relationship-candidates/" + candidate.path("id").asText(); }
    private JsonNode getData(String path) throws Exception {
        return data(mvc.perform(get(base() + path).header(HttpHeaders.AUTHORIZATION, bearer())).andExpect(status().isOk()));
    }
    private ResultActions postJson(String path, JsonNode body) throws Exception {
        return mvc.perform(post(base() + path).header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body)));
    }
    private ResultActions putJson(String path, JsonNode body) throws Exception {
        return mvc.perform(put(base() + path).header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body)));
    }
    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsByteArray()).path("data");
    }
    private String base() { return "/api/v1/works/" + workId; }
    private String bearer() { return "Bearer " + token; }
    private record Source(UUID factId, UUID characterId) {}
}
