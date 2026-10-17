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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
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
import org.monitoring.catchholebackend.domain.character.service.CharacterService;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipAutomationProcessor;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipProcessingScheduler;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipProcessingStateRepository;
import org.monitoring.catchholebackend.domain.relationship.service.RelationshipProcessingService;
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
    @DisplayName("명시적 배우자는 현재 설정 저장과 분리해 자동 확정하며 반복 실행해도 중복되지 않는다")
    void explicitSpouseAutomaticallyConfirmsOnce() throws Exception {
        source(aria, "profile.family", "배우자: 보린", true);
        assertThat(relations()).isEmpty();
        assertThat(getData("/relationship-processing").path("state").asText()).isEqualTo("PENDING");

        processing.process(workId);

        JsonNode relationship = only(relations());
        assertThat(relationship.path("kind").asText()).isEqualTo("SPOUSE");
        assertThat(relationship.path("validity").asText()).isEqualTo("VALID");
        assertThat(relationship.path("evidence").get(0).path("hasEvidence").asBoolean()).isFalse();
        JsonNode candidate = only(candidates());
        assertThat(candidate.path("origin").asText()).isEqualTo("CURRENT_SETTING_RULE");
        assertThat(candidate.path("automaticallyConfirmed").asBoolean()).isTrue();
        assertThat(getData("/relationship-processing").path("autoConfirmedCount").asInt()).isEqualTo(1);

        processing.process(workId);
        processing.requestProcessing(memberId, workId);
        processing.process(workId);
        assertThat(only(relations()).path("id")).isEqualTo(relationship.path("id"));
        assertThat(candidates()).hasSize(1);
        assertThat(getData("/relationship-processing").path("autoConfirmedCount").asInt()).isZero();
    }

    @Test
    @DisplayName("부모와 자녀의 양쪽 명시적 표현은 하나의 관계에 근거 두 개로 합쳐진다")
    void inverseParentStatementsDeduplicate() throws Exception {
        source(aria, "profile.family", "자녀: 보린", true);
        source(borin, "profile.family", "부모: 아리아", true);
        processing.process(workId);

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
        processing.process(workId);
        assertThat(only(relations()).path("kind").asText()).isEqualTo("SIBLING");
        assertThat(jdbc.queryForObject("select count(*) from characters where work_id = ?", Long.class, workId))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("같은 자녀의 친부모와 양부모는 서로 충돌시키지 않고 구분해 보존한다")
    void biologicalAndAdoptiveParentsCoexist() throws Exception {
        source(borin, "profile.family", "친아버지: 아리아; 양어머니: 셀린", true);
        processing.process(workId);
        assertThat(relations()).hasSize(2);
        assertThat(relations().stream().map(row -> row.path("subtype").asText())).containsExactlyInAnyOrder("BIOLOGICAL", "ADOPTIVE");
        assertThat(relations()).allSatisfy(row -> assertThat(row.path("validity").asText()).isEqualTo("VALID"));
    }

    @Test
    @DisplayName("동명이인과 자기 자신을 지칭하는 관계는 임의 연결하지 않고 후보로 남긴다")
    void duplicateNamesAndSelfReferencesRemainPending() throws Exception {
        tx.executeWithoutResult(transaction -> character(works.getOwnedWorkForUpdate(workId, memberId), "보린"));
        source(aria, "profile.family", "배우자: 보린; 형제: 아리아", true);
        processing.process(workId);
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
        processing.process(workId);
        assertThat(relations()).isEmpty();
        assertThat(candidates()).hasSize(2).allSatisfy(row ->
                assertThat(strings(row.path("reviewReasons"))).contains("CONFLICTING"));
    }

    @Test
    @DisplayName("나중에 부정 근거가 저장되면 대기 중부터 기존 자동 관계를 보류하고 재처리 후에도 보류한다")
    void laterContradictionInvalidatesExistingAutomaticSupport() throws Exception {
        source(aria, "profile.family", "보린의 부모", true);
        processing.process(workId);
        String relationId = only(relations()).path("id").asText();
        source(borin, "profile.family", "아리아의 자녀가 아니다", true);
        assertThat(getData("/relationships/" + relationId).path("validity").asText()).isEqualTo("REVIEW_REQUIRED");

        processing.process(workId);

        assertThat(getData("/relationships/" + relationId).path("validity").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(candidates()).hasSize(2);
        assertThat(candidates().stream().filter(row -> row.path("automaticallyConfirmed").asBoolean()).toList())
                .singleElement().satisfies(row -> assertThat(strings(row.path("reviewReasons"))).contains("CONFLICTING"));
    }

    @Test
    @DisplayName("한 처리 묶음의 조상 순환은 입력 순서와 무관하게 모든 간선을 보류한다")
    void ancestryCycleHoldsAllNewEdges() throws Exception {
        source(aria, "profile.family", "자녀: 보린", true);
        source(borin, "profile.family", "자녀: 셀린", true);
        source(celine, "profile.family", "자녀: 아리아", true);
        processing.process(workId);
        assertThat(relations()).isEmpty();
        assertThat(candidates()).hasSize(3).allSatisfy(row ->
                assertThat(strings(row.path("reviewReasons"))).contains("ANCESTRY_CYCLE"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("철회한 부모 관계는 동일 또는 갱신 출처의 재처리에서도 새 관계의 순환 경로로 쓰지 않는다")
    void withdrawnAutomaticRelationDoesNotCreatePhantomAncestryCycle(boolean replaceOriginalSource) throws Exception {
        source(aria, "profile.family", "자녀: 보린", true);
        processing.process(workId);
        JsonNode withdrawn = only(relations());
        postJson("/relationships/" + withdrawn.path("id").asText() + "/withdraw",
                revision(withdrawn.path("version").asLong())).andExpect(status().isOk());
        if (replaceOriginalSource) {
            source(aria, "profile.family", "자녀: 보린", true);
        }
        source(borin, "profile.family", "자녀: 아리아", true);

        processing.process(workId);

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
        processing.process(workId);
        JsonNode edited = candidates().stream().filter(row -> row.path("subjectCharacterId").asText().equals(aria.toString())).findFirst().orElseThrow();
        JsonNode dismissed = candidates().stream().filter(row -> row.path("subjectCharacterId").asText().equals(borin.toString())).findFirst().orElseThrow();
        postJson(candidatePath(dismissed) + "/dismiss", revision(0)).andExpect(status().isOk());
        ObjectNode update = request(aria, celine, "SPOUSE", spouse);
        update.remove("requestId");
        update.put("expectedVersion", 0);
        putJson(candidatePath(edited), update).andExpect(status().isOk());

        processing.requestProcessing(memberId, workId);
        processing.process(workId);

        assertThat(candidates()).hasSize(2);
        assertThat(getData(candidatePath(dismissed)).path("status").asText()).isEqualTo("DISMISSED");
        assertThat(getData(candidatePath(edited)).path("objectCharacterId").asText()).isEqualTo(celine.toString());
        assertThat(getData(candidatePath(edited)).path("status").asText()).isEqualTo("PENDING");
        assertThat(relations()).isEmpty();
    }

    @Test
    @DisplayName("현재 슬롯 교체는 과거 자동 관계를 보류하고 새 관계와 Fact 이력을 함께 남긴다")
    void changedCurrentSlotReplacesSupportWithoutErasingHistory() throws Exception {
        Source before = source(aria, "profile.family", "배우자: 보린", true);
        processing.process(workId);
        String oldId = only(relations()).path("id").asText();
        Source after = source(aria, "profile.family", "배우자: 셀린", true);
        processing.process(workId);

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
        processing.process(workId);
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
        processing.recordFailure(workId);
        assertThat(getData("/relationship-processing").path("state").asText()).isEqualTo("FAILED");
        assertThat(ready()).doesNotContain(workId);
        processing.process(workId);
        assertThat(getData("/relationship-processing").path("processedVersion").asLong()).isZero();

        source(aria, "profile.family", "배우자: 보린", true);
        assertThat(ready()).contains(workId);
        assertThat(getData("/relationship-processing").path("state").asText()).isEqualTo("PENDING");
        processing.process(workId);
        JsonNode complete = getData("/relationship-processing");
        assertThat(complete.path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(complete.path("processedVersion")).isEqualTo(complete.path("inputVersion"));
        assertThat(complete.path("failureCode").isNull()).isTrue();
        assertThat(complete.path("autoConfirmedCount").asInt()).isEqualTo(1);
        assertThat(only(relations()).path("validity").asText()).isEqualTo("VALID");
        assertThat(ready()).doesNotContain(workId);

        processing.requestProcessing(memberId, workId);
        processing.recordFailure(workId);
        processing.requestProcessing(memberId, workId);
        assertThat(ready()).contains(workId);
        processing.process(workId);
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
            assertThatThrownBy(() -> processing.process(workId)).isInstanceOf(IllegalStateException.class);
            assertThat(candidates()).isEmpty();
            assertThat(relations()).isEmpty();
            assertThat(states.findByWorkId(workId)).isEmpty();

            // 다른 테스트 작품을 처리하지 않도록 scheduler의 대상 선택만 좁힌다.
            RelationshipProcessingStateRepository queue = mock(RelationshipProcessingStateRepository.class);
            when(queue.findReadyWorkIds(any(), any())).thenReturn(List.of(workId));
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
        processing.process(workId);

        assertThat(getData("/relationship-processing").path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(only(relations()).path("validity").asText()).isEqualTo("VALID");
        assertThat(candidates()).hasSize(1);
    }

    @Test
    @DisplayName("작품 영구 삭제는 새 처리 상태와 관계 원장을 함께 정리한다")
    void workPurgeDeletesProcessingState() throws Exception {
        source(aria, "profile.family", "배우자: 보린", true);
        processing.process(workId);
        assertThat(states.findByWorkId(workId)).isPresent();
        jdbc.execute("create table if not exists episode_chunks (id uuid primary key, episode_id uuid not null)");
        tx.executeWithoutResult(transaction -> purge.purgeWorkData(workId));
        assertThat(states.findByWorkId(workId)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from relationship_candidates where work_id = ?", Long.class, workId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from works where id = ?", Long.class, foreignWorkId)).isEqualTo(1);
        processing.process(workId);
        processing.recordFailure(workId);
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
        processing.process(workId);
        String id = only(relations()).path("id").asText();
        characterService.archiveCharacter(memberId, workId, borin);
        assertThat(getData("/relationships/" + id).path("validity").asText()).isEqualTo("ARCHIVED_ENDPOINT");
        processing.process(workId);
        characterService.restoreCharacter(memberId, workId, borin);
        assertThat(getData("/relationships/" + id).path("validity").asText()).isEqualTo("REVIEW_REQUIRED");
        processing.process(workId);
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

    private List<UUID> ready() { return states.findReadyWorkIds(LocalDateTime.now(), PageRequest.of(0, 1000)); }
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
