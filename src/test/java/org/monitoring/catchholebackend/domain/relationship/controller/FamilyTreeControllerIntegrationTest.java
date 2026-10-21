package org.monitoring.catchholebackend.domain.relationship.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.argThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSourceManager;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.relationship.dto.response.FamilyTreeResponse;
import org.monitoring.catchholebackend.domain.relationship.entity.CharacterRelationship;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipCandidate;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipEvidence;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphReader;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipSourceResolver;
import org.monitoring.catchholebackend.domain.relationship.service.FamilyTreeService;
import org.monitoring.catchholebackend.domain.relationship.service.RelationshipProcessingService;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipAssertion;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldimage.entity.PrivateWorldImage;
import org.monitoring.catchholebackend.domain.worldimage.repository.CharacterImageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
@DisplayName("가계도 요약의 권한·현재성·범위·변경 감지·동일 스냅샷 계약")
class FamilyTreeControllerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired WorkRepository works;
    @Autowired JwtTokenProvider jwt;
    @Autowired CharacterSnapshotAccessor snapshots;
    @Autowired CharacterSnapshotSourceManager sourceManager;
    @Autowired RelationshipSourceResolver sourceResolver;
    @Autowired FamilyTreeService familyTree;
    @Autowired RelationshipProcessingService processing;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean RelationshipGraphReader graphReader;
    @MockitoSpyBean CharacterImageRepository characterImages;

    private final ObjectMapper json = new ObjectMapper();
    private Long memberId;
    private UUID workId;
    private UUID foreignWorkId;
    private UUID parent;
    private UUID child;
    private UUID spouse;
    private UUID sibling;
    private UUID isolated;
    private UUID foreignCharacter;
    private String token;
    private int sourceIndex;

    @BeforeEach
    void createWork() {
        tx.executeWithoutResult(transaction -> {
            Member owner = Member.register(UUID.randomUUID() + "@family-tree.test", "test-password", null, "합성 작가");
            Member other = Member.register(UUID.randomUUID() + "@family-tree.test", "test-password", null, "다른 작가");
            em.persist(owner);
            em.persist(other);
            Work work = Work.create(owner, "가계도 합성 작품", WorkGenre.FANTASY, null);
            Work foreign = Work.create(other, "다른 작품", WorkGenre.FANTASY, null);
            em.persist(work);
            em.persist(foreign);
            memberId = owner.getId();
            workId = work.getId();
            foreignWorkId = foreign.getId();
            parent = character(work, "아주 긴 이름을 그대로 보존하는 인물 ".repeat(3));
            child = character(work, "자녀");
            spouse = character(work, "배우자");
            sibling = character(work, "형제");
            isolated = character(work, "독립 인물");
            foreignCharacter = character(foreign, "다른 작품 인물");
            token = jwt.generateAccessToken(owner);
        });
    }

    @Test
    @DisplayName("같은 이름의 미등록 후보는 명시적 묶기 전에는 별개이며 묶기·해제 후에도 모든 간선과 근거가 유지된다")
    void explicitlyGroupsMentionsAndReversiblyUngroupsWithoutMergingEdges() throws Exception {
        source(child, "profile.family", "태조의 5대손");
        source(spouse, "profile.family", "태조의 후손");
        processCurrentInput();
        var members = candidates();
        var original = tree("");
        assertThat(original.path("nodes")).hasSize(4);
        var body = groupRequest(members);
        var grouped = data(postJson("/relationship-mention-groups", body).andExpect(status().isOk()));
        assertThat(grouped.path("version").asInt()).isZero();
        assertThat(grouped.path("candidateIds")).hasSize(2);
        assertThat(data(postJson("/relationship-mention-groups", body).andExpect(status().isOk())).path("id")).isEqualTo(grouped.path("id"));
        var tree = tree("");
        assertThat(tree.path("nodes")).hasSize(3);
        assertThat(tree.path("edges")).hasSize(2);
        assertThat(rows(tree.path("edges")).stream().map(edge -> edge.path("id").asText()))
                .containsExactlyElementsOf(rows(original.path("edges")).stream().map(edge -> edge.path("id").asText()).toList());
        assertThat(rows(tree.path("nodes")).stream().filter(node -> node.path("nodeType").asText().equals("MENTION")))
                .singleElement().satisfies(node -> {
                    assertThat(node.path("id").asText()).isEqualTo("mention-group:" + grouped.path("id").asText());
                    assertThat(node.path("candidateId").isNull()).isTrue();
                    assertThat(node.path("candidateIds")).hasSize(2);
                    assertThat(node.path("mentionGroupId")).isEqualTo(grouped.path("id"));
                });
        assertThat(tree.path("revision")).isNotEqualTo(original.path("revision"));
        for (var member : members) {
            var current = getData("/relationship-candidates/" + member.path("id").asText());
            assertThat(current.path("mentionGroupId")).isEqualTo(grouped.path("id"));
            assertThat(current.path("version").asInt()).isEqualTo(member.path("version").asInt() + 1);
            assertThat(current.path("evidence")).isEqualTo(member.path("evidence"));
        }
        assertThat(getData("/relationship-mention-groups")).hasSize(1);
        postJson("/relationship-mention-groups/" + grouped.path("id").asText() + "/ungroup", revision(10))
                .andExpect(status().isConflict());
        var ungrouped = data(postJson("/relationship-mention-groups/" + grouped.path("id").asText() + "/ungroup", revision(0))
                .andExpect(status().isOk()));
        assertThat(ungrouped.path("active").asBoolean()).isFalse();
        assertThat(ungrouped.path("candidateIds")).isEmpty();
        postJson("/relationship-mention-groups/" + grouped.path("id").asText() + "/ungroup", revision(0)).andExpect(status().isOk());
        assertThat(tree("").path("nodes")).hasSize(4);
        assertThat(tree("").path("edges")).hasSize(2);
        assertThat(getData("/relationship-mention-groups")).isEmpty();
        assertThat(data(postJson("/relationship-mention-groups", body).andExpect(status().isOk())).path("active").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from characters where work_id = ?", Long.class, workId)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from character_relationships where work_id = ?", Long.class, workId)).isZero();
    }

    @Test
    @DisplayName("묶기 요청은 소유권·현재 후보 버전·중복 선택·기존 묶음을 검증한다")
    void guardsGroupingOwnershipVersionsAndExplicitMembership() throws Exception {
        source(child, "profile.family", "태조의 5대손");
        source(spouse, "profile.family", "태조의 후손");
        processCurrentInput();
        var body = groupRequest(candidates());
        mvc.perform(post("/api/v1/works/" + foreignWorkId + "/relationship-mention-groups")
                .header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().isNotFound());
        var stale = body.deepCopy();
        ((ObjectNode) stale.path("candidates").get(0)).put("expectedVersion", 100);
        postJson("/relationship-mention-groups", stale).andExpect(status().isConflict());
        var duplicate = body.deepCopy();
        duplicate.putArray("candidates").add(body.path("candidates").get(0)).add(body.path("candidates").get(0));
        postJson("/relationship-mention-groups", duplicate).andExpect(status().isBadRequest());
        var group = data(postJson("/relationship-mention-groups", body).andExpect(status().isOk()));
        postJson("/relationship-mention-groups", groupRequest(candidates())).andExpect(status().isConflict());
        body.put("name", "다른 이름");
        postJson("/relationship-mention-groups", body).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/works/" + foreignWorkId + "/relationship-mention-groups/" + group.path("id").asText())
                .header(HttpHeaders.AUTHORIZATION, bearer())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("명시적 묶기로 생기는 조상 순환은 후보 버전·그룹 저장과 함께 롤백한다")
    void rejectsCyclesIntroducedBySharedMentionIdentity() throws Exception {
        source(child, "profile.family", "부모: 태조");
        source(spouse, "profile.family", "자녀: 태조");
        processCurrentInput();
        confirm(create(child, spouse, "PARENT_OF", "UNSPECIFIED", "CURRENT"));
        var members = candidates().stream().filter(candidate -> candidate.path("objectCharacterId").isNull()).toList();
        var result = postJson("/relationship-mention-groups", groupRequest(members)).andExpect(status().isConflict());
        assertThat(result.andReturn().getResponse().getContentAsString()).contains("ANCESTRY_CYCLE");
        assertThat(getData("/relationship-mention-groups")).isEmpty();
        assertThat(candidates().stream().filter(candidate -> candidate.path("objectCharacterId").isNull()))
                .allSatisfy(candidate -> {
                    assertThat(candidate.path("version").asInt()).isZero();
                    assertThat(candidate.path("mentionGroupId").isNull()).isTrue();
                });
    }

    @Test
    @DisplayName("알려진 부모 경로와 묶인 조상의 세대 차이가 다르면 생성 때 차단한다")
    void rejectsGenerationConflictsIntroducedByGrouping() throws Exception {
        source(child, "profile.family", "태조의 5대손");
        source(spouse, "profile.family", "태조의 3대손");
        processCurrentInput();
        confirm(create(spouse, child, "PARENT_OF", "UNSPECIFIED", "CURRENT"));
        var members = candidates().stream().filter(candidate -> candidate.path("objectCharacterId").isNull()).toList();
        var result = postJson("/relationship-mention-groups", groupRequest(members)).andExpect(status().isConflict());
        assertThat(result.andReturn().getResponse().getContentAsString()).contains("GENERATION_GAP_CONFLICT");
        assertThat(getData("/relationship-mention-groups")).isEmpty();
    }

    @Test
    @DisplayName("묶기 이후 생긴 세대 충돌은 조회에서 숨기며 묶음 목록에서 사유 확인과 해제가 가능하다")
    void revalidatesGroupingAfterOtherRelationshipsChange() throws Exception {
        source(child, "profile.family", "태조의 5대손");
        source(spouse, "profile.family", "태조의 3대손");
        processCurrentInput();
        var group = data(postJson("/relationship-mention-groups", groupRequest(candidates())).andExpect(status().isOk()));
        confirm(create(spouse, child, "PARENT_OF", "UNSPECIFIED", "CURRENT"));
        assertThat(tree("").path("edges")).hasSize(1);
        assertThat(getData("/relationship-mention-groups/" + group.path("id").asText()).path("reviewReasons").toString())
                .contains("GENERATION_GAP_CONFLICT");
        postJson("/relationship-mention-groups/" + group.path("id").asText() + "/ungroup", revision(group.path("version").asLong()))
                .andExpect(status().isOk());
        assertThat(tree("").path("edges")).hasSize(3);
        assertThat(tree("").path("nodes")).hasSize(4);
    }

    @Test
    @DisplayName("현재 근거가 사라진 후보는 새로 묶지 못하며 기존 묶음의 남은 유효 근거만 표시한다")
    void preservesSourceBoundaryForGrouping() throws Exception {
        source(child, "profile.family", "태조의 5대손");
        source(spouse, "profile.family", "태조의 후손");
        processCurrentInput();
        var members = candidates();
        source(child, "profile.family", "관계가 바뀐 현재 설정");
        postJson("/relationship-mention-groups", groupRequest(members)).andExpect(status().isConflict());
        assertThat(getData("/relationship-mention-groups")).isEmpty();
    }

    @Test
    @DisplayName("후보 제외는 해당 구성원만 떼고 그룹 버전을 올려 오래된 해제 요청을 차단한다")
    void detachesChangedCandidateAndGuardsGroupVersion() throws Exception {
        source(child, "profile.family", "태조의 5대손");
        source(spouse, "profile.family", "태조의 후손");
        processCurrentInput();
        var group = data(postJson("/relationship-mention-groups", groupRequest(candidates())).andExpect(status().isOk()));
        var member = candidates().getFirst();
        var dismissed = data(postJson("/relationship-candidates/" + member.path("id").asText() + "/dismiss",
                revision(member.path("version").asLong())).andExpect(status().isOk()));
        assertThat(dismissed.path("mentionGroupId").isNull()).isTrue();
        var remaining = getData("/relationship-mention-groups/" + group.path("id").asText());
        assertThat(remaining.path("candidateIds")).hasSize(1);
        assertThat(remaining.path("version").asInt()).isEqualTo(1);
        postJson("/relationship-mention-groups/" + group.path("id").asText() + "/ungroup", revision(0)).andExpect(status().isConflict());
        postJson("/relationship-mention-groups/" + group.path("id").asText() + "/ungroup", revision(1)).andExpect(status().isOk());
    }

    private ObjectNode groupRequest(List<JsonNode> members) {
        var body = json.createObjectNode().put("requestId", UUID.randomUUID().toString()).put("name", "태조");
        var selected = body.putArray("candidates");
        members.forEach(member -> selected.addObject().put("candidateId", member.path("id").asText())
                .put("expectedVersion", member.path("version").asLong()));
        return body;
    }

    @Test
    @DisplayName("등록 조상·미등록 조상·세대 미상의 세 관계는 중간 인물 없이 별도 조상 간선으로 표시한다")
    void projectsExplicitAncestryWithoutIntermediatePeople() throws Exception {
        UUID ancestor = addCharacter("아르덴");
        source(child, "profile.특징", "아르덴의 5대손 자녀");
        source(spouse, "profile.family", "태조의 7대손");
        source(sibling, "profile.family", "아르덴의 후손");
        processCurrentInput();
        var result = tree("");
        assertThat(result.path("edges")).hasSize(3);
        assertThat(result.path("nodes")).hasSize(5);
        assertThat(rows(result.path("edges"))).allSatisfy(edge -> {
            assertThat(edge.path("kind").asText()).isEqualTo("ANCESTOR_OF");
            assertThat(edge.path("temporalScope").asText()).isEqualTo("CURRENT");
            assertThat(edge.path("subjectRole").isNull()).isTrue();
            assertThat(edge.path("objectRole").isNull()).isTrue();
        });
        var confirmed = rows(result.path("edges")).stream().filter(edge -> edge.path("objectCharacterId").asText().equals(child.toString())).findFirst().orElseThrow();
        assertThat(confirmed.path("subjectCharacterId").asText()).isEqualTo(ancestor.toString());
        assertThat(confirmed.path("generationGap").asInt()).isEqualTo(5);
        var mention = rows(result.path("edges")).stream().filter(edge -> edge.path("sourceType").asText().equals("MENTION")).findFirst().orElseThrow();
        assertThat(mention.path("subjectCharacterId").isNull()).isTrue();
        assertThat(mention.path("objectCharacterId").asText()).isEqualTo(spouse.toString());
        assertThat(mention.path("generationGap").asInt()).isEqualTo(7);
        assertThat(rows(result.path("edges")).stream().filter(edge -> edge.path("objectCharacterId").asText().equals(sibling.toString())))
                .singleElement().satisfies(edge -> assertThat(edge.path("generationGap").isNull()).isTrue());
        assertThat(jdbc.queryForObject("select count(*) from characters where work_id = ?", Long.class, workId)).isEqualTo(6);
    }

    @Test
    @DisplayName("미등록 조상의 부정·추측·가문 문구와 다른 인물의 명제는 가계도에 표시하지 않는다")
    void excludesUnsafeAncestryFromTree() throws Exception {
        source(child, "profile.family", "태조의 후손이 아니다");
        source(spouse, "profile.family", "태조의 후손일지도 모른다");
        source(sibling, "profile.family", "청연 가문의 후손");
        source(isolated, "profile.family", "태조의 5대손 다른사람");
        processCurrentInput();
        assertThat(tree("").path("edges")).isEmpty();
        assertThat(tree("").path("nodes")).isEmpty();
    }

    @Test
    @DisplayName("기본 요약은 현재 유효 관계와 긴 이름을 보존하며 원문·근거·설정을 내보내지 않는다")
    void returnsOnlyCurrentSummaryAndOriginalNames() throws Exception {
        JsonNode confirmed = confirm(create(child, parent, "CHILD_OF", "BIOLOGICAL", "CURRENT"));
        create(parent, spouse, "SPOUSE", "UNSPECIFIED", "CURRENT");

        JsonNode tree = tree("");

        assertThat(tree.path("nodes")).hasSize(2);
        assertThat(tree.path("edges")).hasSize(1);
        assertThat(tree.path("totalNodeCount").asInt()).isEqualTo(2);
        assertThat(tree.path("totalEdgeCount").asInt()).isEqualTo(1);
        assertThat(tree.path("pendingCandidateCount").asLong()).isEqualTo(1);
        assertThat(tree.path("limit").asInt()).isEqualTo(100);
        assertThat(tree.path("includeHistorical").asBoolean()).isFalse();
        assertThat(tree.path("truncated").asBoolean()).isFalse();
        assertThat(tree.path("focusCharacterId").isNull()).isTrue();
        assertThat(tree.path("revision").asText()).matches("[a-f0-9]{64}");
        JsonNode edge = tree.path("edges").get(0);
        assertThat(edge.path("id").asText()).isEqualTo(confirmed.path("confirmedRelationshipId").asText());
        assertThat(edge.path("subjectCharacterId").asText()).isEqualTo(parent.toString());
        assertThat(edge.path("objectCharacterId").asText()).isEqualTo(child.toString());
        assertThat(edge.path("kind").asText()).isEqualTo("PARENT_OF");
        assertThat(edge.path("subtype").asText()).isEqualTo("BIOLOGICAL");
        assertThat(edge.path("subjectRole").asText()).isEqualTo("PARENT");
        assertThat(edge.path("objectRole").asText()).isEqualTo("CHILD");
        assertThat(rows(tree.path("nodes")).stream().filter(node -> node.path("id").asText().equals(parent.toString())))
                .singleElement().satisfies(node -> assertThat(node.path("name").asText())
                        .isEqualTo("아주 긴 이름을 그대로 보존하는 인물 ".repeat(3)));
        assertThat(tree.toString()).doesNotContain("sourceFact", "sourceToken", "evidence", "factValue", "합성 비공개 프로필");
        assertThat(getData("/relationships/" + edge.path("id").asText()).path("evidence")).hasSize(1);
    }

    @Test
    @DisplayName("표시되는 등록 인물의 프로필 이미지만 한 번에 조회하고 기본 이미지는 경로 없이 유지한다")
    void batchesImagesOnlyForVisibleCharacters() throws Exception {
        confirm(create(child, parent, "CHILD_OF", "BIOLOGICAL", "CURRENT"));
        confirm(create(spouse, sibling, "SPOUSE", "UNSPECIFIED", "CURRENT"));
        String catalogId = seedCharacterImage();
        chooseImage(parent, json.createObjectNode().put("catalogId", catalogId).put("version", 0));
        clearInvocations(characterImages);

        JsonNode tree = tree("?focusCharacterId=" + child);

        verify(characterImages).findSelections(argThat(ids -> ids.size() == 2 && ids.contains(parent) && ids.contains(child)));
        JsonNode parentImage = node(tree, parent).path("image");
        assertThat(parentImage.path("catalogId").asText()).isEqualTo(catalogId);
        assertThat(parentImage.path("source").asText()).isEqualTo("MANUAL");
        assertThat(parentImage.path("thumbnailUrl").asText()).isEqualTo("/api/v1/world-image-assets/" + "a".repeat(64) + ".webp");
        assertThat(parentImage).isEqualTo(getData("/characters/" + parent).path("image"));
        JsonNode defaultImage = node(tree, child).path("image");
        assertThat(defaultImage.path("source").asText()).isEqualTo("AUTO");
        assertThat(defaultImage.path("thumbnailUrl").isNull()).isTrue();
        assertThat(defaultImage.path("privateImageId").isNull()).isTrue();
    }

    @Test
    @DisplayName("사진만 바꾸면 관계와 입력 세대는 유지하고 표시 revision만 바뀌며 기본 복귀도 즉시 반영한다")
    void imageChangesUpdateRevisionWithoutChangingRelationships() throws Exception {
        confirm(create(child, parent, "CHILD_OF", "BIOLOGICAL", "CURRENT"));
        JsonNode before = tree("");
        long inputVersion = processing.getStatus(memberId, workId).inputVersion();
        String catalogId = seedCharacterImage();
        JsonNode selected = chooseImage(parent, json.createObjectNode().put("catalogId", catalogId).put("version", 0));

        JsonNode after = tree("");
        assertThat(after.path("revision")).isNotEqualTo(before.path("revision"));
        assertThat(after.path("edges")).isEqualTo(before.path("edges"));
        assertThat(processing.getStatus(memberId, workId).inputVersion()).isEqualTo(inputVersion);
        assertThat(tree("")).isEqualTo(after);

        chooseImage(isolated, json.createObjectNode().put("catalogId", catalogId).put("version", 0));
        assertThat(tree("").path("revision")).isEqualTo(after.path("revision"));
        chooseImage(parent, json.createObjectNode().put("useDefault", true).put("version", selected.path("version").asLong()));
        JsonNode cleared = tree("");
        assertThat(cleared.path("revision")).isNotEqualTo(after.path("revision"));
        assertThat(node(cleared, parent).path("image").path("source").asText()).isEqualTo("DEFAULT");
        assertThat(node(cleared, parent).path("image").path("thumbnailUrl").isNull()).isTrue();
        assertThat(cleared.path("edges")).isEqualTo(before.path("edges"));
    }

    @Test
    @DisplayName("개인 프로필 이미지는 기존 인증 파일 경로로 표시하며 외부 공개 URL이나 파일명을 응답하지 않는다")
    void returnsPrivateImageContractForFocusedCharacter() throws Exception {
        UUID imageId = UUID.randomUUID();
        tx.executeWithoutResult(transaction -> em.persist(PrivateWorldImage.createAccountImage(
                imageId, em.find(Work.class, workId), "비공개 인물 사진.png", 120, 80)));
        JsonNode selected = chooseImage(isolated, json.createObjectNode().put("privateImageId", imageId.toString()).put("version", 0));

        JsonNode tree = tree("?focusCharacterId=" + isolated);

        assertThat(tree.path("edges")).isEmpty();
        assertThat(node(tree, isolated).path("image")).isEqualTo(selected);
        assertThat(selected.path("source").asText()).isEqualTo("PRIVATE");
        assertThat(selected.path("privateImageId").asText()).isEqualTo(imageId.toString());
        assertThat(selected.path("thumbnailUrl").asText()).isEqualTo(base() + "/private-world-images/" + imageId + "/thumbnail");
        assertThat(selected.path("vaultId").isNull()).isTrue();
        assertThat(tree.toString()).doesNotContain("비공개 인물 사진", "https://", "encryptedMetadata", "storageAttemptId");
    }

    @Test
    @DisplayName("과거 포함은 유효한 HISTORICAL만 추가하며 UNKNOWN이나 과거를 현재로 추정하지 않는다")
    void historicalOptionExcludesUnknownScope() throws Exception {
        confirm(create(parent, child, "PARENT_OF", "ADOPTIVE", "CURRENT"));
        JsonNode historical = create(parent, spouse, "SPOUSE", "UNSPECIFIED", "HISTORICAL");
        confirm(historical);
        addUnknownScopeLegacyRelationship();

        assertThat(tree("").path("edges")).hasSize(1);
        JsonNode withHistory = tree("?includeHistorical=true");
        assertThat(withHistory.path("edges")).hasSize(2);
        assertThat(rows(withHistory.path("edges")).stream().map(edge -> edge.path("temporalScope").asText()))
                .containsExactlyInAnyOrder("CURRENT", "HISTORICAL");
        assertThat(rows(withHistory.path("edges")).stream().filter(edge -> edge.path("temporalScope").asText().equals("HISTORICAL")))
                .singleElement().satisfies(edge -> {
                    assertThat(edge.path("timeLabel").asText()).isEqualTo("10년 전");
                    assertThat(edge.path("subjectRole").isNull()).isTrue();
                    assertThat(edge.path("objectRole").isNull()).isTrue();
                });
        assertThat(withHistory.path("includeHistorical").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("초점은 필터 뒤 연결된 가족만 반환하고 독립 인물은 한 노드로 유지하며 후보 수는 작품 전체다")
    void focusScopesConnectedFamilyAndKeepsIsolatedCharacter() throws Exception {
        confirm(create(parent, child, "PARENT_OF", "UNSPECIFIED", "CURRENT"));
        confirm(create(spouse, sibling, "SIBLING", "HALF", "CURRENT"));
        confirm(create(child, spouse, "SPOUSE", "UNSPECIFIED", "HISTORICAL"));
        create(spouse, isolated, "SPOUSE", "UNSPECIFIED", "CURRENT");

        JsonNode focused = tree("?focusCharacterId=" + parent);
        assertThat(focused.path("nodes")).hasSize(2);
        assertThat(focused.path("edges")).hasSize(1);
        assertThat(focused.path("totalEdgeCount").asInt()).isEqualTo(1);
        assertThat(focused.path("pendingCandidateCount").asInt()).isEqualTo(1);
        assertThat(focused.path("focusCharacterId").asText()).isEqualTo(parent.toString());
        assertThat(tree("?focusCharacterId=" + parent + "&includeHistorical=true").path("edges")).hasSize(3);
        JsonNode alone = tree("?focusCharacterId=" + isolated);
        assertThat(alone.path("nodes")).hasSize(1);
        assertThat(alone.path("nodes").get(0).path("id").asText()).isEqualTo(isolated.toString());
        assertThat(alone.path("edges")).isEmpty();
        assertThat(alone.path("totalNodeCount").asInt()).isEqualTo(1);
        assertThat(alone.path("truncated").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("표시 상한은 간선에 적용하고 전체 합계·양 끝 노드·안정된 선택을 유지한다")
    void capsEdgesWithoutDanglingEndpointsOrLosingTotals() throws Exception {
        confirm(create(parent, child, "PARENT_OF", "BIOLOGICAL", "CURRENT"));
        confirm(create(child, spouse, "SPOUSE", "UNSPECIFIED", "CURRENT"));
        confirm(create(spouse, sibling, "SIBLING", "FULL", "CURRENT"));

        JsonNode limited = tree("?limit=1&focusCharacterId=" + parent);

        assertThat(limited.path("edges")).hasSize(1);
        assertThat(limited.path("nodes")).hasSize(2);
        assertThat(limited.path("edges").get(0).path("subjectCharacterId").asText()).isEqualTo(parent.toString());
        assertThat(limited.path("totalEdgeCount").asInt()).isEqualTo(3);
        assertThat(limited.path("totalNodeCount").asInt()).isEqualTo(4);
        assertThat(limited.path("truncated").asBoolean()).isTrue();
        assertThat(tree("?limit=1&focusCharacterId=" + parent)).isEqualTo(limited);
        assertThat(tree("?limit=200").path("edges")).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WITHDRAWN", "ARCHIVED", "STALE_SOURCE"})
    @DisplayName("철회·보관·현재 근거 소실 관계는 가계도와 집계에서 제외한다")
    void excludesInvalidRelationships(String invalidation) throws Exception {
        JsonNode candidate = create(parent, child, "PARENT_OF", "UNSPECIFIED", "CURRENT");
        String relationId = confirm(candidate).path("confirmedRelationshipId").asText();
        String before = tree("").path("revision").asText();
        if (invalidation.equals("WITHDRAWN")) {
            JsonNode relation = getData("/relationships/" + relationId);
            postJson("/relationships/" + relationId + "/withdraw", revision(relation.path("version").asLong()))
                    .andExpect(status().isOk());
        } else {
            tx.executeWithoutResult(transaction -> {
                Work work = works.getOwnedWorkForUpdate(workId, memberId);
                WorkCharacter character = em.find(WorkCharacter.class, parent);
                if (invalidation.equals("ARCHIVED")) character.archive();
                else snapshots.replace(character, new LinkedHashMap<>(), true);
                work.markRelationshipInputChanged();
            });
        }

        JsonNode after = tree("?includeHistorical=true");
        assertThat(after.path("nodes")).isEmpty();
        assertThat(after.path("edges")).isEmpty();
        assertThat(after.path("totalEdgeCount").asInt()).isZero();
        assertThat(tree("").path("revision").asText()).isNotEqualTo(before);
    }

    @Test
    @DisplayName("자동 관계는 후처리 대기·실패 중 제외하며 완료 후 다시 포함한다")
    void automaticSupportWaitsForCurrentInputGeneration() throws Exception {
        source(child, "profile.family", "배우자: 배우자");
        processing.process(workId, processing.getStatus(memberId, workId).inputVersion());
        assertThat(tree("").path("edges")).hasSize(1);
        source(isolated, "profile.job", "정원사");
        long input = processing.getStatus(memberId, workId).inputVersion();
        assertThat(tree("").path("edges")).isEmpty();
        processing.recordFailure(workId, input);
        assertThat(tree("").path("edges")).isEmpty();
        processing.requestProcessing(memberId, workId);
        processing.process(workId, input);
        assertThat(tree("").path("edges")).hasSize(1);
    }

    @Test
    @DisplayName("수동 확정과 근거 추가·이름 수정·철회는 같은 요약의 revision을 바꾼다")
    void revisionDetectsManualActionsAndCurrentNames() throws Exception {
        JsonNode candidate = create(parent, child, "PARENT_OF", "UNSPECIFIED", "CURRENT");
        String pendingRevision = tree("").path("revision").asText();
        String relationId = confirm(candidate).path("confirmedRelationshipId").asText();
        String confirmedRevision = tree("").path("revision").asText();
        assertThat(confirmedRevision).isNotEqualTo(pendingRevision);
        confirm(create(child, parent, "CHILD_OF", "UNSPECIFIED", "CURRENT"));
        String supportedRevision = tree("").path("revision").asText();
        assertThat(supportedRevision).isNotEqualTo(confirmedRevision);
        tx.executeWithoutResult(transaction -> {
            works.getOwnedWorkForUpdate(workId, memberId);
            em.find(WorkCharacter.class, parent).updateBasicInfo("바뀐 이름", null);
        });
        assertThat(tree("").path("revision").asText()).isNotEqualTo(supportedRevision);
        JsonNode relation = getData("/relationships/" + relationId);
        postJson("/relationships/" + relationId + "/withdraw", revision(relation.path("version").asLong())).andExpect(status().isOk());
        assertThat(tree("").path("edges")).isEmpty();
    }

    @Test
    @DisplayName("관계 조회 중 확정이 커밋돼도 노드·간선·후보 수는 같은 DB 스냅샷을 따른다")
    void concurrentConfirmationDoesNotMixGraphAndCandidateCounts() throws Exception {
        JsonNode candidate = create(parent, child, "PARENT_OF", "UNSPECIFIED", "CURRENT");
        var graphRead = new CountDownLatch(1);
        var mutationCommitted = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            var graph = (RelationshipGraphReader.Graph) invocation.callRealMethod();
            if (workId.equals(invocation.getArgument(0)) && first.compareAndSet(true, false)) {
                graphRead.countDown();
                assertThat(mutationCommitted.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return graph;
        }).when(graphReader).readGraph(any(UUID.class));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var reading = executor.submit(() -> familyTree.getFamilyTree(memberId, workId, false, null, 100));
            try {
                assertThat(graphRead.await(10, TimeUnit.SECONDS)).isTrue();
                confirm(candidate);
            } finally {
                mutationCommitted.countDown();
            }
            FamilyTreeResponse before = reading.get(10, TimeUnit.SECONDS);
            assertThat(before.nodes()).isEmpty();
            assertThat(before.edges()).isEmpty();
            assertThat(before.pendingCandidateCount()).isEqualTo(1);
            FamilyTreeResponse after = familyTree.getFamilyTree(memberId, workId, false, null, 100);
            assertThat(after.edges()).hasSize(1);
            assertThat(after.pendingCandidateCount()).isZero();
            assertThat(after.revision()).isNotEqualTo(before.revision());
        } finally {
            doCallRealMethod().when(graphReader).readGraph(any(UUID.class));
        }
    }

    @Test
    @DisplayName("인증과 작품 소유권을 검증하고 다른 작품·보관·없는 초점 인물을 404로 숨긴다")
    void rejectsUnauthorizedWorksAndUnavailableFocus() throws Exception {
        mvc.perform(get(base() + "/family-tree")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/works/" + foreignWorkId + "/family-tree").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/works/" + UUID.randomUUID() + "/family-tree").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound());
        tx.executeWithoutResult(transaction -> em.find(WorkCharacter.class, isolated).archive());
        for (UUID id : List.of(foreignCharacter, isolated, UUID.randomUUID())) {
            mvc.perform(get(base() + "/family-tree?focusCharacterId=" + id).header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isNotFound());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit=0", "limit=201", "limit=-1", "limit=no", "includeHistorical=nope", "focusCharacterId=no"})
    @DisplayName("잘못된 표시 상한·시점·초점 값은 400으로 거절한다")
    void rejectsInvalidQuery(String query) throws Exception {
        mvc.perform(get(base() + "/family-tree?" + query).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("얀델의 아들인 비요른은 얀델을 등록하지 않아도 근거 후보를 가진 부모 방향 언급으로 보인다")
    void displaysExplicitUnregisteredParentWithoutCreatingCharacterOrRelationship() throws Exception {
        UUID bjorn = addCharacter("비요른");
        Source original = source(bjorn, "profile.family", "얀델의 아들");
        processCurrentInput();

        JsonNode candidate = onlyCandidate();
        String candidateId = candidate.path("id").asText();
        assertThat(candidate.path("status").asText()).isEqualTo("PENDING");
        assertThat(candidate.path("origin").asText()).isEqualTo("CURRENT_SETTING_RULE");
        assertThat(candidate.path("kind").asText()).isEqualTo("CHILD_OF");
        assertThat(candidate.path("objectCharacterId").isNull()).isTrue();
        assertThat(candidate.path("objectMention").asText()).isEqualTo("얀델");
        assertThat(candidate.path("displayableAsMention").asBoolean()).isTrue();
        assertThat(candidate.path("automaticallyConfirmed").asBoolean()).isFalse();
        JsonNode detail = getData("/relationship-candidates/" + candidateId);
        assertThat(detail.path("displayableAsMention").asBoolean()).isTrue();
        assertThat(rows(detail.path("evidence"))).singleElement().satisfies(evidence -> {
            assertThat(evidence.path("sourceFactId").asText()).isEqualTo(original.factId().toString());
            assertThat(evidence.path("current").asBoolean()).isTrue();
            assertThat(evidence.path("currentValue").path("factValue").asText()).isEqualTo("얀델의 아들");
        });

        JsonNode tree = tree("");
        assertThat(rows(tree.path("nodes"))).hasSize(2);
        JsonNode mention = rows(tree.path("nodes")).stream()
                .filter(node -> node.path("nodeType").asText().equals("MENTION")).findFirst().orElseThrow();
        assertThat(mention.path("id").asText()).isEqualTo("mention:" + candidateId);
        assertThat(mention.path("name").asText()).isEqualTo("얀델");
        assertThat(mention.path("characterId").isNull()).isTrue();
        assertThat(mention.path("candidateId").asText()).isEqualTo(candidateId);
        assertThat(mention.path("image").isNull()).isTrue();
        assertThat(rows(tree.path("nodes")).stream().filter(node -> node.path("nodeType").asText().equals("CHARACTER")))
                .singleElement().satisfies(node -> {
                    assertThat(node.path("id").asText()).isEqualTo(bjorn.toString());
                    assertThat(node.path("characterId").asText()).isEqualTo(bjorn.toString());
                    assertThat(node.path("candidateId").isNull()).isTrue();
                    assertThat(node.path("image").path("thumbnailUrl").isNull()).isTrue();
                });
        assertThat(rows(tree.path("edges"))).singleElement().satisfies(edge -> {
            assertThat(edge.path("sourceType").asText()).isEqualTo("MENTION");
            assertThat(edge.path("relationshipId").isNull()).isTrue();
            assertThat(edge.path("candidateId").asText()).isEqualTo(candidateId);
            assertThat(edge.path("kind").asText()).isEqualTo("PARENT_OF");
            assertThat(edge.path("subjectNodeId").asText()).isEqualTo(mention.path("id").asText());
            assertThat(edge.path("objectNodeId").asText()).isEqualTo(bjorn.toString());
            assertThat(edge.path("subjectCharacterId").isNull()).isTrue();
            assertThat(edge.path("objectCharacterId").asText()).isEqualTo(bjorn.toString());
            assertThat(edge.path("subjectRole").asText()).isEqualTo("PARENT");
            assertThat(edge.path("objectRole").asText()).isEqualTo("SON");
        });
        assertThat(tree.path("optionalLinkCount").asInt()).isEqualTo(1);
        assertThat(tree.path("pendingCandidateCount").asInt()).isEqualTo(1);
        assertThat(tree("")).isEqualTo(tree);
        assertThat(rows(getData("/relationships?size=100").path("content"))).isEmpty();
        long registeredYandelCount = tx.execute(transaction -> em.createQuery(
                "select count(c) from WorkCharacter c where c.work.id = :work and c.name = :name", Long.class)
                .setParameter("work", workId).setParameter("name", "얀델").getSingleResult());
        assertThat(registeredYandelCount).isZero();
    }

    @Test
    @DisplayName("양쪽 현재 설정에 각각 명시된 아버지와 아들을 한 정규화 관계의 끝점으로 제공한다")
    void returnsBothExplicitRolesFromRegisteredEndpoints() throws Exception {
        UUID yandel = addCharacter("얀델");
        UUID bjorn = addCharacter("비요른");
        source(yandel, "profile.family", "아들: 비요른");
        source(bjorn, "profile.family", "아버지: 얀델");
        processCurrentInput();

        assertThat(rows(tree("").path("edges"))).singleElement().satisfies(edge -> {
            assertThat(edge.path("sourceType").asText()).isEqualTo("RELATIONSHIP");
            assertThat(edge.path("subjectCharacterId").asText()).isEqualTo(yandel.toString());
            assertThat(edge.path("objectCharacterId").asText()).isEqualTo(bjorn.toString());
            assertThat(edge.path("subjectRole").asText()).isEqualTo("FATHER");
            assertThat(edge.path("objectRole").asText()).isEqualTo("SON");
        });
        assertThat(candidates()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("같은 명제의 서로 다른 호칭은 중립으로 표시하고 자동 후보·관계를 중복 저장하지 않는다")
    void roleConflictsDoNotChangeAutomaticCandidateIdentity(boolean registered) throws Exception {
        if (registered) addCharacter("얀델");
        source(child, "profile.family", "아버지: 얀델; 어머니: 얀델");
        processCurrentInput();

        assertThat(candidates()).hasSize(1);
        assertThat(rows(tree("").path("edges"))).singleElement().satisfies(edge -> {
            assertThat(edge.path("subjectRole").asText()).isEqualTo("PARENT");
            assertThat(edge.path("objectRole").asText()).isEqualTo("CHILD");
            assertThat(edge.path("sourceType").asText()).isEqualTo(registered ? "RELATIONSHIP" : "MENTION");
        });
        assertThat(rows(getData("/relationships?size=100").path("content"))).hasSize(registered ? 1 : 0);
        processing.requestProcessing(memberId, workId);
        processCurrentInput();
        assertThat(candidates()).hasSize(1);
        assertThat(rows(tree("").path("edges"))).hasSize(1);
    }

    @Test
    @DisplayName("뒤늦게 같은 이름의 캐릭터가 등록되어도 언급을 유지하고 직접 선택·확정한 뒤에만 실제 관계로 바뀐다")
    void lateRegistrationRequiresExplicitCandidateUpdateAndConfirmation() throws Exception {
        source(child, "profile.family", "얀델의 아들");
        processCurrentInput();
        JsonNode candidate = onlyCandidate();
        JsonNode before = tree("");
        UUID yandel = addCharacter("얀델");
        processing.requestProcessing(memberId, workId);
        processCurrentInput();

        JsonNode stillPending = getData("/relationship-candidates/" + candidate.path("id").asText());
        assertThat(stillPending.path("objectCharacterId").isNull()).isTrue();
        assertThat(stillPending.path("displayableAsMention").asBoolean()).isTrue();
        JsonNode afterRegistration = tree("");
        assertThat(afterRegistration.path("nodes")).isEqualTo(before.path("nodes"));
        assertThat(afterRegistration.path("edges")).isEqualTo(before.path("edges"));
        assertThat(afterRegistration.path("optionalLinkCount").asInt()).isEqualTo(1);

        ObjectNode update = json.createObjectNode().put("expectedVersion", stillPending.path("version").asLong())
                .put("subjectCharacterId", child.toString()).put("objectCharacterId", yandel.toString())
                .put("kind", "CHILD_OF").put("subtype", "UNSPECIFIED")
                .put("temporalScope", "CURRENT").put("assertion", "ASSERTED");
        var evidence = update.putArray("evidence");
        stillPending.path("evidence").forEach(item -> evidence.addObject()
                .put("sourceFactId", item.path("sourceFactId").asText()).put("sourceToken", item.path("sourceToken").asText()));
        String path = "/relationship-candidates/" + candidate.path("id").asText();
        JsonNode selected = data(putJson(path, update).andExpect(status().isOk()));
        assertThat(selected.path("displayableAsMention").asBoolean()).isFalse();
        JsonNode confirmed = data(postJson(path + "/confirm", revision(selected.path("version").asLong()))
                .andExpect(status().isOk()));

        JsonNode after = tree("");
        assertThat(rows(after.path("nodes"))).hasSize(2).allSatisfy(node ->
                assertThat(node.path("nodeType").asText()).isEqualTo("CHARACTER"));
        assertThat(rows(after.path("edges"))).singleElement().satisfies(edge -> {
            assertThat(edge.path("sourceType").asText()).isEqualTo("RELATIONSHIP");
            assertThat(edge.path("relationshipId")).isEqualTo(confirmed.path("confirmedRelationshipId"));
            assertThat(edge.path("candidateId").isNull()).isTrue();
            assertThat(edge.path("subjectNodeId").asText()).isEqualTo(yandel.toString());
            assertThat(edge.path("objectNodeId").asText()).isEqualTo(child.toString());
        });
        assertThat(after.path("optionalLinkCount").asInt()).isZero();
        assertThat(after.path("pendingCandidateCount").asInt()).isZero();
        assertThat(after.path("revision")).isNotEqualTo(afterRegistration.path("revision"));
    }

    @Test
    @DisplayName("서로 다른 인물이 말한 동명 미등록 부모는 후보별로 분리하며 이름만으로 형제나 가족을 합치지 않는다")
    void sameNameMentionsFromDifferentSubjectsStaySeparate() throws Exception {
        source(child, "profile.family", "얀델의 아들");
        source(sibling, "profile.family", "얀델의 딸");
        processCurrentInput();

        JsonNode all = tree("");
        assertThat(rows(all.path("nodes"))).hasSize(4);
        assertThat(rows(all.path("nodes")).stream().filter(node -> node.path("nodeType").asText().equals("MENTION")))
                .hasSize(2).allSatisfy(node -> assertThat(node.path("name").asText()).isEqualTo("얀델"))
                .extracting(node -> node.path("id").asText()).doesNotHaveDuplicates();
        assertThat(rows(all.path("edges"))).hasSize(2).allSatisfy(edge ->
                assertThat(edge.path("kind").asText()).isEqualTo("PARENT_OF"));

        JsonNode focused = tree("?focusCharacterId=" + child);
        assertThat(rows(focused.path("nodes"))).hasSize(2);
        assertThat(rows(focused.path("nodes"))).noneSatisfy(node ->
                assertThat(node.path("id").asText()).isEqualTo(sibling.toString()));
        assertThat(rows(focused.path("edges"))).hasSize(1);
        assertThat(focused.path("optionalLinkCount").asInt()).isEqualTo(2);
        assertThat(focused.path("pendingCandidateCount").asInt()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"얀델의 아들일지도 모른다", "얀델의 아들이 아니다", "얀델의 아들 같은 존재",
            "부모: 미상", "부모: 그 사람", "부모: 아버지", "자녀의 아들"})
    @DisplayName("추측·부정·비유·미상·대명사·가족호칭·자기 언급은 미등록 인물 도형으로 승격하지 않는다")
    void excludesUncertainNegativeFigurativeGenericAndSelfMentions(String profile) throws Exception {
        source(child, "profile.family", profile);
        processCurrentInput();

        JsonNode tree = tree("?includeHistorical=true");
        assertThat(rows(tree.path("nodes"))).isEmpty();
        assertThat(rows(tree.path("edges"))).isEmpty();
        assertThat(tree.path("optionalLinkCount").asInt()).isZero();
        for (JsonNode candidate : candidates()) {
            assertThat(candidate.path("displayableAsMention").asBoolean()).isFalse();
            assertThat(getData("/relationship-candidates/" + candidate.path("id").asText())
                    .path("displayableAsMention").asBoolean()).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"STALE_SOURCE", "ARCHIVED_SUBJECT", "ARCHIVED_TARGET", "RENAMED_TO_SELF", "DISMISSED"})
    @DisplayName("현재 근거 교체·보관·자기 이름으로 변경·후보 제외는 다음 조회부터 언급과 선택 연결 개수에서 빠진다")
    void removesMentionAsSoonAsItsSourceOrStatusBecomesInvalid(String change) throws Exception {
        source(child, "profile.family", "얀델의 아들");
        processCurrentInput();
        JsonNode candidate = onlyCandidate();
        String path = "/relationship-candidates/" + candidate.path("id").asText();
        JsonNode before = tree("");
        switch (change) {
            case "STALE_SOURCE" -> source(child, "profile.family", "가족에 관한 설정이 바뀌었다");
            case "ARCHIVED_SUBJECT" -> archive(child);
            case "ARCHIVED_TARGET" -> archive(addCharacter("얀델"));
            case "RENAMED_TO_SELF" -> tx.executeWithoutResult(transaction -> {
                Work work = works.getOwnedWorkForUpdate(workId, memberId);
                em.find(WorkCharacter.class, child).updateBasicInfo("얀델", null);
                work.markRelationshipInputChanged();
            });
            case "DISMISSED" -> postJson(path + "/dismiss", revision(candidate.path("version").asLong()))
                    .andExpect(status().isOk());
            default -> throw new AssertionError(change);
        }

        JsonNode after = tree("?includeHistorical=true");
        assertThat(rows(after.path("nodes"))).isEmpty();
        assertThat(rows(after.path("edges"))).isEmpty();
        assertThat(after.path("optionalLinkCount").asInt()).isZero();
        assertThat(after.path("pendingCandidateCount").asInt()).isEqualTo(change.equals("DISMISSED") ? 0 : 1);
        assertThat(after.path("revision")).isNotEqualTo(before.path("revision"));
        JsonNode detail = getData(path);
        assertThat(detail.path("displayableAsMention").asBoolean()).isFalse();
        if (change.equals("STALE_SOURCE")) {
            assertThat(rows(detail.path("evidence"))).singleElement().satisfies(item -> {
                assertThat(item.path("current").asBoolean()).isFalse();
                assertThat(item.path("currentValue").isNull()).isTrue();
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"얀델의 아들이 아니다", "얀델의 부모"})
    @DisplayName("새 현재 설정의 부정 또는 반대 부모 방향은 자동 재처리 전부터 기존 언급을 가린다")
    void newConflictingCurrentSourceImmediatelyHidesMention(String conflict) throws Exception {
        source(child, "profile.family", "얀델의 아들");
        processCurrentInput();
        JsonNode candidate = onlyCandidate();
        assertThat(rows(tree("").path("edges"))).hasSize(1);

        source(child, "profile.family_conflict", conflict);

        assertThat(rows(tree("").path("edges"))).isEmpty();
        assertThat(getData("/relationship-candidates/" + candidate.path("id").asText())
                .path("displayableAsMention").asBoolean()).isFalse();
        processCurrentInput();
        assertThat(rows(tree("?includeHistorical=true").path("edges"))).isEmpty();
        assertThat(candidates()).allSatisfy(row -> assertThat(row.path("displayableAsMention").asBoolean()).isFalse());
    }

    @Test
    @DisplayName("여러 근거 중 하나만 오래되어도 나머지 현재 근거만으로 언급을 유지하지 않는다")
    void requiresEveryEvidenceSourceToRemainCurrent() throws Exception {
        source(child, "profile.family", "얀델의 아들");
        processCurrentInput();
        JsonNode candidate = onlyCandidate();
        Source additional = source(child, "profile.family_secondary", "얀델의 아들");
        tx.executeWithoutResult(transaction -> {
            works.getOwnedWorkForUpdate(workId, memberId);
            em.persist(RelationshipEvidence.create(em.find(RelationshipCandidate.class, UUID.fromString(candidate.path("id").asText())),
                    em.find(CharacterFact.class, additional.factId()), additional.token()));
        });
        assertThat(rows(tree("").path("edges"))).hasSize(1);

        source(child, "profile.family_secondary", "현재 가족 설정 없음");

        JsonNode detail = getData("/relationship-candidates/" + candidate.path("id").asText());
        assertThat(rows(detail.path("evidence"))).hasSize(2)
                .extracting(item -> item.path("current").asBoolean()).containsExactlyInAnyOrder(true, false);
        assertThat(detail.path("displayableAsMention").asBoolean()).isFalse();
        assertThat(rows(tree("").path("edges"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANUAL", "UNKNOWN_TIME", "CONFLICTING", "AMBIGUOUS_TARGET"})
    @DisplayName("같은 현재 근거가 있어도 수동 후보·시점 불명·모순·동명이인 보류 후보는 자동 언급이 아니다")
    void requiresRuleOriginKnownTimeAndOnlyMissingTargetReason(String excluded) throws Exception {
        Source source = source(child, "profile.family", "얀델의 아들");
        tx.executeWithoutResult(transaction -> {
            Work work = works.getOwnedWorkForUpdate(workId, memberId);
            var candidate = RelationshipCandidate.create(work, UUID.randomUUID(), "c".repeat(64),
                    em.find(WorkCharacter.class, child), null, "얀델", RelationshipKind.CHILD_OF,
                    RelationshipSubtype.UNSPECIFIED,
                    excluded.equals("UNKNOWN_TIME") ? RelationshipTemporalScope.UNKNOWN : RelationshipTemporalScope.CURRENT,
                    null, excluded.equals("CONFLICTING") ? RelationshipAssertion.CONFLICTING : RelationshipAssertion.ASSERTED, null);
            if (!excluded.equals("MANUAL")) candidate.markRuleGenerated();
            candidate.holdAutomatically(excluded.equals("AMBIGUOUS_TARGET") ? "AMBIGUOUS_TARGET" : "UNRESOLVED_TARGET");
            em.persist(candidate);
            em.persist(RelationshipEvidence.create(candidate, em.find(CharacterFact.class, source.factId()), source.token()));
        });

        assertThat(onlyCandidate().path("displayableAsMention").asBoolean()).isFalse();
        JsonNode tree = tree("?includeHistorical=true");
        assertThat(rows(tree.path("edges"))).isEmpty();
        assertThat(tree.path("optionalLinkCount").asInt()).isZero();
        assertThat(tree.path("pendingCandidateCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("과거·초점·상한은 표시 범위만 바꾸고 작품 전체 선택 연결 수와 전체 대기 수는 유지한다")
    void keepsWorkWideOptionalCountAcrossHistoryFocusAndTruncation() throws Exception {
        source(child, "profile.family", "얀델의 아들");
        source(sibling, "profile.family", "에단의 딸");
        source(spouse, "profile.family", "전 배우자: 루시아");
        source(isolated, "profile.family", "미확인 인물의 아들일지도 모른다");
        processCurrentInput();

        JsonNode limited = tree("?limit=1");
        assertThat(rows(limited.path("edges"))).hasSize(1);
        assertThat(rows(limited.path("nodes"))).hasSize(2);
        assertThat(limited.path("totalEdgeCount").asInt()).isEqualTo(2);
        assertThat(limited.path("totalNodeCount").asInt()).isEqualTo(4);
        assertThat(limited.path("truncated").asBoolean()).isTrue();
        assertThat(limited.path("optionalLinkCount").asInt()).isEqualTo(3);
        assertThat(limited.path("pendingCandidateCount").asInt()).isEqualTo(4);
        List<String> ids = rows(limited.path("nodes")).stream().map(node -> node.path("id").asText()).toList();
        assertThat(rows(limited.path("edges"))).allSatisfy(edge ->
                assertThat(ids).contains(edge.path("subjectNodeId").asText(), edge.path("objectNodeId").asText()));

        JsonNode historical = tree("?includeHistorical=true");
        assertThat(rows(historical.path("edges"))).hasSize(3);
        assertThat(historical.path("optionalLinkCount").asInt()).isEqualTo(3);
        JsonNode currentFocus = tree("?focusCharacterId=" + spouse);
        assertThat(rows(currentFocus.path("edges"))).isEmpty();
        assertThat(rows(currentFocus.path("nodes"))).hasSize(1);
        assertThat(currentFocus.path("optionalLinkCount").asInt()).isEqualTo(3);
        JsonNode pastFocus = tree("?includeHistorical=true&focusCharacterId=" + spouse);
        assertThat(rows(pastFocus.path("edges"))).singleElement().satisfies(edge ->
                assertThat(edge.path("temporalScope").asText()).isEqualTo("HISTORICAL"));
        assertThat(pastFocus.path("optionalLinkCount").asInt()).isEqualTo(3);
        assertThat(pastFocus.path("pendingCandidateCount").asInt()).isEqualTo(4);
        assertThat(tree("?limit=1")).isEqualTo(limited);
    }

    private UUID addCharacter(String name) {
        return tx.execute(transaction -> {
            Work work = works.getOwnedWorkForUpdate(workId, memberId);
            UUID id = character(work, name);
            work.markRelationshipInputChanged();
            return id;
        });
    }

    private void archive(UUID characterId) {
        tx.executeWithoutResult(transaction -> {
            Work work = works.getOwnedWorkForUpdate(workId, memberId);
            em.find(WorkCharacter.class, characterId).archive();
            work.markRelationshipInputChanged();
        });
    }

    private void processCurrentInput() { processing.process(workId, processing.getStatus(memberId, workId).inputVersion()); }
    private List<JsonNode> candidates() throws Exception { return rows(getData("/relationship-candidates?size=100").path("content")); }
    private JsonNode onlyCandidate() throws Exception {
        List<JsonNode> candidates = candidates();
        assertThat(candidates).hasSize(1);
        return candidates.getFirst();
    }

    private UUID character(Work work, String name) {
        WorkCharacter character = WorkCharacter.create(work, name, null, null, null, null, null, null, null, null, null);
        em.persist(character);
        return character.getId();
    }

    private Source source(UUID characterId, String key, String value) {
        return tx.execute(transaction -> {
            works.getOwnedWorkForUpdate(workId, memberId);
            WorkCharacter character = em.find(WorkCharacter.class, characterId);
            ObjectNode valueJson = json.createObjectNode().put("value", value);
            CharacterFact fact = CharacterFact.createManual(character, CharacterFactType.PROFILE, key, value, valueJson);
            em.persist(fact);
            var slot = new CharacterSnapshotSlot(CharacterFactType.PROFILE, key);
            var current = new LinkedHashMap<>(snapshots.read(character));
            current.put(slot, new CharacterSnapshotEntry(slot, value, valueJson, true));
            snapshots.replace(character, current, true);
            sourceManager.replaceSources(character, slot, List.of(fact));
            String sourceToken = sourceResolver.findCurrentSources(workId, characterId).stream()
                    .filter(context -> context.factId().equals(fact.getId())).findFirst().orElseThrow().contextToken();
            return new Source(fact.getId(), sourceToken);
        });
    }

    private JsonNode create(UUID subject, UUID object, String kind, String subtype, String temporal) throws Exception {
        Source source = source(subject, "profile.family_" + sourceIndex++, "합성 비공개 프로필");
        ObjectNode body = json.createObjectNode().put("requestId", UUID.randomUUID().toString())
                .put("subjectCharacterId", subject.toString()).put("objectCharacterId", object.toString())
                .put("kind", kind).put("subtype", subtype).put("temporalScope", temporal).put("assertion", "ASSERTED");
        if (temporal.equals("HISTORICAL")) body.put("timeLabel", "10년 전");
        body.putArray("evidence").addObject().put("sourceFactId", source.factId().toString()).put("sourceToken", source.token());
        return data(postJson("/relationship-candidates", body).andExpect(status().isOk()));
    }

    private void addUnknownScopeLegacyRelationship() {
        Source source = source(sibling, "profile.legacy", "합성 레거시 설정");
        tx.executeWithoutResult(transaction -> {
            Work work = works.getOwnedWorkForUpdate(workId, memberId);
            var subject = em.find(WorkCharacter.class, sibling);
            var object = em.find(WorkCharacter.class, isolated);
            var relation = CharacterRelationship.create(work, subject, object, RelationshipKind.SIBLING,
                    RelationshipSubtype.UNSPECIFIED, RelationshipTemporalScope.UNKNOWN, null, "a".repeat(64));
            em.persist(relation);
            var candidate = RelationshipCandidate.create(work, UUID.randomUUID(), "b".repeat(64), subject, object, null,
                    RelationshipKind.SIBLING, RelationshipSubtype.UNSPECIFIED, RelationshipTemporalScope.UNKNOWN,
                    null, RelationshipAssertion.ASSERTED, null);
            candidate.confirm(relation);
            em.persist(candidate);
            em.persist(RelationshipEvidence.create(candidate, em.find(CharacterFact.class, source.factId()), source.token()));
        });
    }

    private JsonNode confirm(JsonNode candidate) throws Exception {
        return data(postJson("/relationship-candidates/" + candidate.path("id").asText() + "/confirm", revision(0))
                .andExpect(status().isOk()));
    }
    private ObjectNode revision(long version) { return json.createObjectNode().put("expectedVersion", version); }
    private String seedCharacterImage() {
        String id = "family-tree-" + UUID.randomUUID();
        jdbc.update("INSERT INTO world_image_catalog(id,category,name,search_text,is_default,active,thumbnail_sha,image_sha,created_at,updated_at) VALUES (?,?,?, ?,false,true,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id, "RACE", "합성 프로필", "합성 프로필", "a".repeat(64), "b".repeat(64));
        return id;
    }
    private JsonNode chooseImage(UUID characterId, JsonNode body) throws Exception {
        return data(mvc.perform(patch(base() + "/characters/" + characterId + "/image")
                .header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))).andExpect(status().isOk()));
    }
    private JsonNode node(JsonNode tree, UUID id) {
        return rows(tree.path("nodes")).stream().filter(node -> node.path("characterId").asText().equals(id.toString()))
                .findFirst().orElseThrow();
    }
    private JsonNode tree(String query) throws Exception { return getData("/family-tree" + query); }
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
    private JsonNode data(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsByteArray()).path("data");
    }
    private List<JsonNode> rows(JsonNode array) {
        List<JsonNode> rows = new ArrayList<>();
        array.forEach(rows::add);
        return rows;
    }
    private String base() { return "/api/v1/works/" + workId; }
    private String bearer() { return "Bearer " + token; }
    private record Source(UUID factId, String token) {}
}
