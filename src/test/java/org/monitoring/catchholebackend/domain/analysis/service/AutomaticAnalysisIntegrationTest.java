package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobCompleteRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobPayload;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.hash.Sha256Hasher;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.domain.character.dto.request.WorkerCharacterFactComparisonBatchCompleteRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.WorkerCharacterFactComparisonFailRequest;
import org.monitoring.catchholebackend.domain.character.service.CharacterFactComparisonWorkerService;
import org.monitoring.catchholebackend.domain.character.exception.OrderedCharacterComparisonClaimException;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingWorkerService;
import org.monitoring.catchholebackend.domain.worldsetting.exception.OrderedWorldSettingComparisonClaimException;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchCompleteRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchContextRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonFailRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingComparisonBatchPayload;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateConfirmRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateGroupConfirmRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateGroupConfirmDecision;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateUpdateRequest;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.service.SettingCandidateService;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactConfirmApplicationMode;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateConfirmRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateDismissRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateDecisionUpdateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateDecisionUpdateItem;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFactComparisonBatch;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.service.CharacterAnalysisStateService;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingValueSemantics;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingSchemaSource;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingCandidateServiceImpl;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonBatch;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecision;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecisionSource;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingService;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingIdentityUpdateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSubjectResolutionType;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingConsolidationStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:automatic-analysis;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("회차별 자동 반영과 다음 회차 문맥의 실제 저장 경계")
class AutomaticAnalysisIntegrationTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String SOURCE = String.join("\n",
            "에르웬이 등장했다.", "에르웬의 본명은 에르웬 미샤다.",
            "에르웬라고 말했다.", "에르웬 미샤라고 말했다.", "비요른라고 말했다.", "데이비스라고 말했다.", " 에르웬 라고 말했다.",
            "stats.mental 수치는 35였다.", "stats.mental 수치는 36였다.", "stats.mental 수치는 50였다.",
            "stats.strength 수치는 25였다.", "정신 수치는 99였다.", "비요른의 정신 99", "에르웬의 정신 35",
            "데이비스는 기절했지만 치료 후 깨어났다.", "파기할 보류 근거",
            "엘프는 북부에 산다.", "해당 종족의 수명을 알 수 없다.", "엘프의 수명을 알 수 없다.", "미궁 원문 근거");
    @Autowired EntityManager entities;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AnalysisJobRepository jobs;
    @Autowired SettingCandidateRepository candidates;
    @Autowired AnalysisRunStateService states;
    @Autowired AnalysisJobService publicJobs;
    @Autowired Sha256Hasher hasher;
    @Autowired CharacterAnalysisStateService characterStates;
    @Autowired SettingCandidateService characterReview;
    @Autowired AnalysisJobWorkerService worker;
    @Autowired WorldSettingWorkerService worldWorker;
    @Autowired WorldSettingService worldSettings;
    @Autowired CharacterFactComparisonWorkerService characterWorker;
    @Autowired org.monitoring.catchholebackend.domain.analysis.processor.AnalysisLiveStateReader liveState;
    /**
     * #387 기본은 format 2다. ANALYSIS_AUTOMATIC_INPUT_LIVE_SETTINGS_ENABLED=false로 같은 시나리오를 format 1에서도 돌린다.
     * 기본 실행의 format 1은 아래 두 #387 테스트가 서비스의 플래그를 직접 바꿔 확인한다.
     */
    @org.springframework.beans.factory.annotation.Value("${analysis.automatic-input.live-settings-enabled:true}") boolean liveInput;
    @Autowired org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal stateJournal;
    @Autowired tools.jackson.databind.ObjectMapper wireJson;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoSpyBean WorldSettingCandidateServiceImpl worldApplication;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.monitoring.catchholebackend.global.storage.ObjectStorageService storage;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.monitoring.catchholebackend.domain.character.repository.StoredCharacterEvidenceRepository storedEvidence;
    private TransactionTemplate tx;

    @BeforeEach
    void prepareTransactions() {
        tx = new TransactionTemplate(transactions);
        // H2 has no PostgreSQL receipt trigger; model that boundary, tested separately on PostgreSQL.
        var verified = org.monitoring.catchholebackend.support.StoredCharacterEvidenceTestSupport.verified(SOURCE);
        org.mockito.Mockito.when(storedEvidence.find(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> verified.find(call.getArgument(0)));
        org.mockito.Mockito.when(storedEvidence.find(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> verified.find(call.getArgument(0), call.getArgument(1)));

        org.mockito.Mockito.when(storage.getText(org.mockito.ArgumentMatchers.startsWith("automatic-test/"))).thenReturn(SOURCE);
        org.mockito.Mockito.when(storage.contentHash(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> hasher.hash(invocation.getArgument(0)));
    }

    private static List<String> currentFactValues(JsonNode input) {
        List<String> values = new ArrayList<>();
        input.path("characters").forEach(character -> character.path("slots").forEach(slot -> {
            if (slot.path("factValue").isTextual()) values.add(slot.path("factValue").asText());
        }));
        return values;
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode evidence(String quote) {
        int start = SOURCE.indexOf(quote);
        if (start < 0) throw new IllegalArgumentException("Missing fixture quote: " + quote);
        return JSON.arrayNode().add(JSON.objectNode().put("quote", quote)
                .put("startOffset", SOURCE.codePointCount(0, start))
                .put("endOffset", SOURCE.codePointCount(0, start + quote.length())));
    }

    @Test
    @DisplayName("3화 실패 뒤 1·2화와 완료 단계를 보존해 재개하고 5화까지 끝난 뒤에만 묶음 후보를 수정한다")
    void resumesThirdEpisodeWithoutReanalyzingCompletedPrefix() {
        Run run = run(5);
        WorkerAnalysisJobPayload first = claim();
        UUID candidateId = tx.execute(status -> addUnresolvedReference(jobs.findById(first.analysisJobId()).orElseThrow()).getId());
        complete(first);
        WorkerAnalysisJobPayload second = claim();
        complete(second);
        WorkerAnalysisJobPayload third = claim();
        JsonNode frozenInput = input(third);
        tx.executeWithoutResult(status -> jobs.findById(third.analysisJobId()).orElseThrow()
                .updateCheckpointStage(AnalysisJobCheckpointStage.CHUNKS_READY));
        worker.failAnalysisJob(third.analysisJobId(), third.leaseToken(),
                new org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobFailRequest(
                        AnalysisFailureCode.UNEXPECTED_ERROR, "저장 연결 일시 실패"));
        Long owner = tx.execute(status -> entities.find(Work.class, run.workId()).getMember().getId());
        assertThat(worker.claimAnalysisJob(request())).isEmpty();
        assertThatThrownBy(() -> characterReview.updateSettingCandidate(owner, run.workId(), candidateId,
                new SettingCandidateUpdateRequest("stats.mental", "77")))
                .isInstanceOfSatisfying(AppException.class, error ->
                        assertThat(error.getResultCode().getCode()).isEqualTo("ANALYSIS_REVIEW_WAIT_REQUIRED"));

        var resumed = publicJobs.retryFailedAnalysisJob(owner, run.workId(), third.analysisJobId());
        assertThat(resumed).singleElement().satisfies(job -> assertThat(job.id()).isEqualTo(third.analysisJobId()));
        WorkerAnalysisJobPayload retry = claim();
        assertThat(retry.analysisJobId()).isEqualTo(third.analysisJobId());
        assertThat(input(retry)).isEqualTo(frozenInput);
        tx.executeWithoutResult(status -> assertThat(jobs.findById(retry.analysisJobId()).orElseThrow().getCheckpointStage())
                .isEqualTo(AnalysisJobCheckpointStage.CHUNKS_READY));
        complete(retry);
        complete(claim());
        complete(claim());
        assertThat(worker.claimAnalysisJob(request())).isEmpty();
        characterReview.updateSettingCandidate(owner, run.workId(), candidateId,
                new SettingCandidateUpdateRequest("stats.mental", "77"));
        tx.executeWithoutResult(status -> {
            assertThat(jobs.count()).isEqualTo(5);
            assertThat(jobs.findAll()).allSatisfy(job -> {
                assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
                assertThat(job.getJournalStatus()).isEqualTo(AnalysisJournalStatus.SEALED);
            });
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unchangedWorldIdentityPreservesRunningAndCompletedAnalysis(boolean completed) {
        Run run = run(2);
        UUID settingId = tx.execute(status -> {
            WorldSetting setting = WorldSetting.create(entities.find(Work.class, run.workId()),
                    WorldSettingCategory.RACE, "바바리안", "서식지", "북부");
            entities.persist(setting);
            return setting.getId();
        });
        Long owner = tx.execute(status -> entities.find(Work.class, run.workId()).getMember().getId());
        WorkerAnalysisJobPayload first = claim();
        if (completed) complete(first);
        JsonNode originalInput = preservedInput(first);
        var unchanged = new WorldSettingIdentityUpdateRequest(WorldSettingCategory.RACE, "  바바리안  ", 0L);
        worldSettings.updateWorldSettingIdentity(owner, run.workId(), settingId, unchanged);
        worldSettings.updateWorldSettingIdentity(owner, run.workId(), settingId, unchanged);
        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            assertThat(job.getStatus()).isEqualTo(completed ? AnalysisJobStatus.SUCCEEDED : AnalysisJobStatus.RUNNING);
            assertThat(job.getJournalStatus()).isEqualTo(completed ? AnalysisJournalStatus.SEALED : AnalysisJournalStatus.PENDING);
            assertThat(jobs.findById(run.jobs().get(1)).orElseThrow().getStatus()).isEqualTo(AnalysisJobStatus.PENDING);
            assertThat(entities.find(WorldSetting.class, settingId).getVersion()).isZero();
        });
        assertThat(preservedInput(first)).isEqualTo(originalInput);
        assertThatThrownBy(() -> worldSettings.updateWorldSettingIdentity(owner, run.workId(), settingId,
                new WorldSettingIdentityUpdateRequest(WorldSettingCategory.RACE, "북부 바바리안", 0L)))
                .isInstanceOfSatisfying(AppException.class, error ->
                        assertThat(error.getResultCode().getCode()).isEqualTo("ANALYSIS_REVIEW_WAIT_REQUIRED"));
        tx.executeWithoutResult(status -> {
            assertThat(jobs.findById(first.analysisJobId()).orElseThrow().getJournalStatus())
                    .isEqualTo(completed ? AnalysisJournalStatus.SEALED : AnalysisJournalStatus.PENDING);
            assertThat(jobs.findById(run.jobs().get(1)).orElseThrow().getStatus()).isEqualTo(AnalysisJobStatus.PENDING);
        });
    }

    @Test
    @DisplayName("앞 회차의 새 캐릭터와 스탯을 저장한 뒤 다음 회차는 실제 ID와 최신값 및 동일인 이름을 읽는다")
    void savesCharacterThenRefreshesActualIdentityValuesAndAliases() {
        Run run = run(3);
        WorkerAnalysisJobPayload first = claim();
        UUID discoveryId = tx.execute(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            SettingCandidate discovery = discovery(job, "에르웬", null, true);
            addStat(job, discovery.getProvisionalSubjectKey(), null, 35, CharacterFactOperation.ADD);
            addWorld(job);
            return discovery.getId();
        });
        complete(first);
        UUID actualId = tx.execute(status -> candidates.findById(discoveryId).orElseThrow().getMatchedCharacterId());
        assertThat(actualId).isNotNull();
        WorkerAnalysisJobPayload second = claim();
        assertThat(second.analysisJobId()).isEqualTo(run.jobs().get(1));
        assertThat(second.knownCharacters()).anySatisfy(character -> assertThat(character.characterId()).isEqualTo(actualId));
        JsonNode secondInput = input(second);
        assertThat(secondInput.path("worldSettings").toString()).contains("북부", "actualWorldSettingId", "AUTOMATIC");
        assertThat(secondInput.path("worldSettings").size()).isEqualTo(1);
        JsonNode world = secondInput.path("worldSettings").elements().next();
        assertThat(world.path("actualWorldSettingId").isTextual()).isTrue();
        assertThat(world.path("propertiesJson").path("서식지").asText()).isEqualTo("북부");
        JsonNode actual = secondInput.path("characters").path("character:" + actualId);
        assertThat(actual.path("slots").path("STAT:stats.mental").path("factValue").asText()).as(actual.toPrettyString()).isEqualTo("35");
        assertThat(actual.path("slots").path("STAT:stats.mental").path("provenance").path("reviewSource").asText())
                .isEqualTo("AUTOMATIC");
        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(second.analysisJobId()).orElseThrow();
            discovery(job, "에르웬 미샤", actualId, false);
            addStat(job, null, actualId, 36, CharacterFactOperation.UPDATE);
        });
        complete(second);
        WorkerAnalysisJobPayload third = claim();
        assertThat(third.knownCharacters()).anySatisfy(character -> {
            assertThat(character.characterId()).isEqualTo(actualId);
            assertThat(character.aliases()).contains("에르웬 미샤");
        });
        JsonNode thirdIdentity = input(third).path("characters").path("character:" + actualId);
        assertThat(thirdIdentity.path("aliases").toString()).contains("에르웬 미샤");
        assertThat(thirdIdentity.path("slots").path("STAT:stats.mental").path("factValue").asText()).isEqualTo("36");
        assertThat(tx.<Long>execute(status -> entities.createQuery("select count(c) from WorkCharacter c", Long.class).getSingleResult())).isEqualTo(1);
        assertThat(tx.<Long>execute(status -> entities.createQuery("select count(f) from CharacterFact f", Long.class).getSingleResult())).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 임시 인물의 두 발견은 각 이름과 근거를 저장하고 다음 회차의 별칭으로 이어진다")
    void provisionalAliasDiscoveriesKeepOriginalNamesAfterAutomaticPromotion() {
        run(2);
        WorkerAnalysisJobPayload first = claim();
        List<UUID> discoveries = tx.execute(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            SettingCandidate anchor = discovery(job, "에르웬", null, true);
            SettingCandidate alias = SettingCandidate.createCharacterDiscovery(job.getWork(), job.getEpisode(), null, job,
                    "에르웬 미샤", "에르웬", null, SettingCandidateMatchStatus.MATCHED,
                    evidence("에르웬의 본명은 에르웬 미샤다."), BigDecimal.ONE, null);
            ReflectionTestUtils.setField(anchor, "evidenceSpans", evidence("에르웬이 등장했다."));
            ReflectionTestUtils.setField(alias, "provisionalSubjectKey", anchor.getProvisionalSubjectKey());
            entities.persist(alias);
            addStat(job, anchor.getProvisionalSubjectKey(), null, 35, CharacterFactOperation.ADD);
            return List.of(anchor.getId(), alias.getId());
        });

        complete(first);

        UUID actualId = tx.execute(status -> {
            SettingCandidate anchor = candidates.findById(discoveries.getFirst()).orElseThrow();
            SettingCandidate alias = candidates.findById(discoveries.getLast()).orElseThrow();
            assertThat(anchor.getEntityName()).isEqualTo("에르웬");
            assertThat(alias.getEntityName()).isEqualTo("에르웬 미샤");
            assertThat(alias.getRawEntityMention()).isEqualTo("에르웬");
            assertThat(alias.getEvidenceSpans().get(0).path("quote").asText()).isEqualTo("에르웬의 본명은 에르웬 미샤다.");
            assertThat(alias.getMatchedCharacterId()).isEqualTo(anchor.getMatchedCharacterId());
            assertThat(alias.getProvisionalSubjectKey()).isNull();
            assertThat(alias.isReviewedAutomatically()).isTrue();
            assertThat(alias.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
            return anchor.getMatchedCharacterId();
        });
        WorkerAnalysisJobPayload second = claim();
        assertThat(second.knownCharacters()).singleElement().satisfies(character -> {
            assertThat(character.characterId()).isEqualTo(actualId);
            assertThat(character.name()).isEqualTo("에르웬");
            assertThat(character.aliases()).containsExactly("에르웬 미샤");
            assertThat(character.identityEvidence()).extracting(span -> span.quote())
                    .contains("에르웬이 등장했다.", "에르웬의 본명은 에르웬 미샤다.");
        });
        assertThat(input(second).path("references").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("미상 보류 후보는 실제 캐릭터와 Fact가 되지 않고 다음 회차의 참고 정보에만 남는다")
    void pendingCandidateOnlyBecomesReference() {
        run(2);
        WorkerAnalysisJobPayload first = claim();
        UUID pending = tx.execute(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            SettingCandidate candidate = SettingCandidate.create(job.getWork(), job.getEpisode(), null, job,
                    SettingEntityType.CHARACTER, "미상", "stats.mental", "99", SettingValueType.NUMBER,
                    JSON.objectNode().put("value", 99), evidence("정신 수치는 99였다."), BigDecimal.ONE, null);
            entities.persist(candidate);
            return candidate.getId();
        });
        complete(first);
        WorkerAnalysisJobPayload second = claim();
        JsonNode input = input(second);
        assertThat(input.path("characters").size()).isZero();
        assertThat(input.path("references").path("pending-character:" + pending).path("value").asText()).isEqualTo("99");
        assertThat(tx.<SettingCandidateReviewStatus>execute(status -> candidates.findById(pending).orElseThrow().getReviewStatus()))
                .isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("새 업로드 묶음의 첫 회차도 이전 묶음의 미확정 근거를 확정 사실과 구분해서 받는다")
    void newBatchReadsEarlierPendingAlongsideConfirmedFacts() {
        Run run = run(1);
        WorkerAnalysisJobPayload first = claim();
        UUID pending = tx.execute(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            var discovered = discovery(job, "에르웬", null, true);
            addStat(job, discovered.getProvisionalSubjectKey(), null, 35, CharacterFactOperation.ADD);
            return addUnresolvedReference(job).getId();
        });
        complete(first);
        UUID secondId = nextBatch(run.workId(), 2);
        WorkerAnalysisJobPayload second = claim();
        assertThat(second.analysisJobId()).isEqualTo(secondId);
        assertThat(second.analysisContext().runId()).isNotEqualTo(first.analysisContext().runId());
        assertThat(second.analysisContext().unresolvedReferences()).singleElement().satisfies(reference -> {
            assertThat(reference.sourceEpisodeNo()).isEqualTo(1);
            assertThat(reference.value()).isEqualTo("99");
            assertThat(reference.evidenceSpans()).singleElement().satisfies(span -> assertThat(span.quote()).contains("99"));
        });
        JsonNode input = input(second);
        assertThat(input.path("references").path("pending-character:" + pending).path("value").asText()).isEqualTo("99");
        assertThat(input.path("characters").elements().next().path("slots").path("STAT:stats.mental").path("factValue").asText())
                .isEqualTo("35");
        assertThat(currentFactValues(input)).doesNotContain("99");
        tx.executeWithoutResult(status -> {
            assertThat(candidates.findById(pending).orElseThrow().getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
            assertThat(entities.createQuery("select count(f) from CharacterFact f", Long.class).getSingleResult()).isEqualTo(1L);
            assertThat(states.prepareInput(jobs.findById(secondId).orElseThrow())).isTrue();
        });
        assertThat(input(second)).isEqualTo(input);
    }

    @Test
    @DisplayName("분석 중 수정은 기다리게 하고 완료 뒤 수정은 후속 결과와 당시 입력을 보존한다")
    void crossBatchHumanEditInvalidatesFrozenInputAndNextRunUsesLatestText() {
        Run run = run(1);
        WorkerAnalysisJobPayload first = claim();
        UUID pending = tx.execute(status -> addUnresolvedReference(jobs.findById(first.analysisJobId()).orElseThrow()).getId());
        complete(first);
        UUID secondId = nextBatch(run.workId(), 2);
        WorkerAnalysisJobPayload second = claim();
        JsonNode frozen = liveInput ? storedInput(second) : input(second);
        String frozenHash = second.analysisContext().inputStateHash();
        Long ownerId = tx.execute(status -> entities.find(Work.class, run.workId()).getMember().getId());

        assertThatThrownBy(() -> characterReview.updateSettingCandidate(ownerId, run.workId(), pending,
                new SettingCandidateUpdateRequest("stats.mental", "77")))
                .isInstanceOfSatisfying(AppException.class, error -> assertThat(error.getResultCode().getCode()).isEqualTo("ANALYSIS_REVIEW_WAIT_REQUIRED"));
        complete(second);
        characterReview.updateSettingCandidate(ownerId, run.workId(), pending, new SettingCandidateUpdateRequest("stats.mental", "77"));

        tx.executeWithoutResult(status -> {
            AnalysisJob secondJob = jobs.findById(secondId).orElseThrow();
            assertThat(secondJob.getJournalStatus()).isEqualTo(AnalysisJournalStatus.SEALED);
            assertThat(secondJob.getStatus()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
            assertThat(secondJob.getAutomaticInputState()).isEqualTo(frozen);
            assertThat(secondJob.getInputStateHash()).isEqualTo(frozenHash);
        });
        // #387 format 2도 확정 설정이 claim 뒤 바뀌지 않았으면(대기 후보 수정은 확정 설정이 아니다) 완료한 Job의 투영을 읽는다.
        tx.executeWithoutResult(status -> assertThat(states.getProjectedState(jobs.findById(secondId).orElseThrow())).isNotNull());
        nextBatch(run.workId(), 3);
        var third = claim();
        JsonNode reference = input(third).path("references").path("pending-character:" + pending);
        assertThat(reference.path("value").asText()).isEqualTo("77");
        assertThat(reference.path("reason").asText()).contains("사용자", "수정 전 원문");
        assertThat(reference.path("evidenceSpans").toString()).contains("99");
        assertThat(input(third).path("characters")).isEmpty();
    }

    @Test
    @DisplayName("이미 제외한 후보의 재요청은 다음 순차 분석 중에도 같은 결과를 반환한다")
    void repeatedDismissalRemainsIdempotentDuringLaterOrderedRun() {
        Run run = run(2);
        WorkerAnalysisJobPayload first = claim();
        UUID dismissedCharacterId = tx.execute(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            SettingCandidate character = addUnresolvedReference(job);
            return character.getId();
        });
        complete(first);
        UUID dismissedWorldId = tx.execute(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            candidates.findById(dismissedCharacterId).orElseThrow().dismiss();
            WorldSettingCandidate world = WorldSettingCandidate.create(
                    job.getWork(), job.getEpisode(), job,
                    WorldSettingCategory.RACE, "고블린", "전투 특징", "무리지어 공격한다.",
                    JSON.arrayNode(), BigDecimal.ONE, null
            );
            entities.persist(world);
            world.dismiss("첫 제외", job.getWork().getMember());
            return world.getId();
        });
        WorkerAnalysisJobPayload second = claim();
        Long owner = tx.execute(status -> entities.find(Work.class, run.workId()).getMember().getId());

        assertThat(characterReview.dismissSettingCandidate(owner, run.workId(), dismissedCharacterId)
                .reviewStatus()).isEqualTo(SettingCandidateReviewStatus.DISMISSED);
        assertThat(worldApplication.dismissCandidate(owner, run.workId(), dismissedWorldId,
                new WorldSettingCandidateDismissRequest("재시도")).reviewStatus())
                .isEqualTo(WorldSettingReviewStatus.DISMISSED);

        tx.executeWithoutResult(status -> {
            assertThat(jobs.findById(second.analysisJobId()).orElseThrow().getStatus())
                    .isEqualTo(AnalysisJobStatus.RUNNING);
            assertThat(candidates.findById(dismissedCharacterId).orElseThrow().getReviewStatus())
                    .isEqualTo(SettingCandidateReviewStatus.DISMISSED);
            assertThat(entities.find(WorldSettingCandidate.class, dismissedWorldId).getReviewNote())
                    .isEqualTo("첫 제외");
        });
    }

    @Test
    @DisplayName("같은 회차를 재분석한 최신 작업이 실패했으면 이전 성공 작업의 후보를 다시 가져오지 않는다")
    void newBatchDoesNotResurrectPendingFromSupersededAnalysis() {
        Run run = run(1);
        WorkerAnalysisJobPayload first = claim();
        tx.executeWithoutResult(status -> addUnresolvedReference(jobs.findById(first.analysisJobId()).orElseThrow()));
        complete(first);
        tx.executeWithoutResult(status -> {
            AnalysisJob old = jobs.findById(first.analysisJobId()).orElseThrow();
            AnalysisJob replacement = AnalysisJob.create(old.getWork(), old.getBatch(), old.getEpisode(), AnalysisJobType.SETTING_EXTRACTION);
            replacement.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
            states.initializeRun(List.of(replacement));
            ReflectionTestUtils.setField(replacement, "status", AnalysisJobStatus.FAILED);
            ReflectionTestUtils.setField(replacement, "createdAt", old.getCreatedAt().plusSeconds(1));
        });
        nextBatch(run.workId(), 2);
        assertThat(input(claim()).path("references")).isEmpty();
    }

    private UUID nextBatch(UUID workId, int episodeNumber) {
        return tx.execute(status -> {
            Work work = entities.find(Work.class, workId);
            UploadBatch batch = UploadBatch.create(work, work.getMember(), UploadType.SINGLE_EPISODE, UploadSourceType.FILE);
            entities.persist(batch);
            Episode episode = Episode.create(work, null, episodeNumber, "다음 원고", "automatic-test/" + episodeNumber,
                    "v1", storage.contentHash(SOURCE), SOURCE.codePointCount(0, SOURCE.length()));
            entities.persist(episode);
            AnalysisJob job = AnalysisJob.create(work, batch, episode, AnalysisJobType.SETTING_EXTRACTION);
            job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
            states.initializeRun(List.of(job));
            return job.getId();
        });
    }

    @ParameterizedTest
    @MethodSource("lateCharacterCases")
    @DisplayName("캐릭터 늦은 확정은 작가의 현재·이력 선택을 반영하고 완료된 후행 분석을 유지한다")
    void lateCharacterConfirmationRespectsChoiceAndKeepsFinishedRun(String scenario, boolean group) {
        Run run = run(2);
        var first = claim();
        UUID pending = tx.execute(status -> addUnresolvedReference(jobs.findById(first.analysisJobId()).orElseThrow()).getId());
        complete(first);
        var second = claim();
        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(second.analysisJobId()).orElseThrow();
            var discovered = discovery(job, "에르웬", null, true);
            if (!List.of("empty", "past", "history", "remove").contains(scenario)) addStat(job, discovered.getProvisionalSubjectKey(), null, 36, CharacterFactOperation.ADD);
        });
        complete(second);
        Long owner = tx.execute(status -> entities.find(Work.class, run.workId()).getMember().getId());
        UUID character = tx.execute(status -> entities.createQuery("select c.id from WorkCharacter c where c.work.id = :work", UUID.class)
                .setParameter("work", run.workId()).getSingleResult());
        tx.executeWithoutResult(status -> {
            if (scenario.equals("past")) ReflectionTestUtils.setField(candidates.findById(pending).orElseThrow(), "temporalScope", CharacterFactTemporalScope.PAST);
            if (scenario.equals("history") || scenario.equals("remove")) {
                var candidate = candidates.findById(pending).orElseThrow();
                ReflectionTestUtils.setField(candidate, "temporalScope", CharacterFactTemporalScope.PRESENT);
                ReflectionTestUtils.setField(candidate, "suggestedOperation", scenario.equals("history")
                        ? CharacterFactOperation.HISTORY_ONLY : CharacterFactOperation.REMOVE);
            }
        });
        characterReview.updateSettingCandidateCharacterMatch(owner, run.workId(), pending,
                new org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateCharacterMatchRequest(
                    org.monitoring.catchholebackend.domain.character.type.SettingCandidateCharacterMatchResolutionType.MATCH_EXISTING, character, null));
        characterReview.updateSettingCandidate(owner, run.workId(), pending, new SettingCandidateUpdateRequest("stats.mental", "35"));
        tx.executeWithoutResult(status -> {
            var entity = entities.find(WorkCharacter.class, character);
            if (scenario.equals("removed") || scenario.equals("manual")) {
                var snapshot = new java.util.LinkedHashMap<CharacterSnapshotSlot, CharacterSnapshotEntry>();
                if (scenario.equals("manual")) snapshot.put(new CharacterSnapshotSlot(CharacterFactType.STAT, "stats.mental"),
                        new CharacterSnapshotEntry(new CharacterSnapshotSlot(CharacterFactType.STAT, "stats.mental"), "88", JSON.objectNode().put("value", 88), true));
                new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor().replace(entity, snapshot);
                entities.createQuery("delete from CharacterSnapshotSource s where s.workCharacter.id = :id").setParameter("id", character).executeUpdate();
            }
        });
        CharacterFactConfirmApplicationMode selectedMode = List.of("past", "history", "remove").contains(scenario)
                ? CharacterFactConfirmApplicationMode.HISTORY_ONLY : CharacterFactConfirmApplicationMode.APPLY_PROPOSAL;
        LocalDateTime beforeReview = tx.execute(status -> candidates.findById(pending).orElseThrow().getUpdatedAt());
        var reviewed = characterReview.updateSettingCandidate(owner, run.workId(), pending,
                new SettingCandidateUpdateRequest("stats.mental", "35", selectedMode, beforeReview));
        UUID batch = tx.execute(status -> jobs.findById(first.analysisJobId()).orElseThrow().getBatch().getId());
        if (group) {
            var omitted = new SettingCandidateGroupConfirmRequest(batch, List.of(new SettingCandidateGroupConfirmDecision(
                    pending, selectedMode, null, false, reviewed.updatedAt())));
            assertThatThrownBy(() -> characterReview.confirmSettingCandidateGroup(owner, run.workId(), omitted))
                    .isInstanceOfSatisfying(AppException.class, error -> assertThat(error.getResultCode())
                            .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_EDIT_APPLICATION_REQUIRED));
            var request = new SettingCandidateGroupConfirmRequest(batch, List.of(new SettingCandidateGroupConfirmDecision(
                    pending, selectedMode, null, true, reviewed.updatedAt())));
            assertThat(characterReview.confirmSettingCandidateGroup(owner, run.workId(), request).recomparisonRequired()).isFalse();
            nextBatch(run.workId(), 3);
            assertThat(characterReview.confirmSettingCandidateGroup(owner, run.workId(), request).recomparisonRequired()).isFalse();
        } else {
            var omitted = new SettingCandidateConfirmRequest(
                    selectedMode, null, false, reviewed.updatedAt());
            assertThatThrownBy(() -> characterReview.confirmSettingCandidate(owner, run.workId(), pending, omitted))
                    .isInstanceOfSatisfying(AppException.class, error -> assertThat(error.getResultCode())
                            .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_EDIT_APPLICATION_REQUIRED));
            var request = new SettingCandidateConfirmRequest(selectedMode, null, true, reviewed.updatedAt());
            assertThat(characterReview.confirmSettingCandidate(owner, run.workId(), pending, request).recomparisonRequired()).isFalse();
            nextBatch(run.workId(), 3);
            assertThat(characterReview.confirmSettingCandidate(owner, run.workId(), pending, request).recomparisonRequired()).isFalse();
        }
        tx.executeWithoutResult(status -> {
            assertThat(candidates.findById(pending).orElseThrow().getConfirmedApplicationMode()).isEqualTo(selectedMode);
            if (scenario.equals("past")) assertThat(candidates.findById(pending).orElseThrow().getTemporalScope()).isEqualTo(CharacterFactTemporalScope.PAST);
            assertThat(jobs.findById(second.analysisJobId()).orElseThrow().getJournalStatus()).isEqualTo(AnalysisJournalStatus.SEALED);
            assertThat(jobs.findById(second.analysisJobId()).orElseThrow().getStatus()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
            assertThat(entities.createQuery("select count(f) from CharacterFact f where f.settingCandidate.id = :candidate", Long.class)
                    .setParameter("candidate", pending).getSingleResult()).isEqualTo(1);
        });
        var third = claim();
        var values = currentFactValues(input(third));
        if (selectedMode == CharacterFactConfirmApplicationMode.APPLY_PROPOSAL) {
            assertThat(values).contains("35").doesNotContain("36", "88");
        } else assertThat(values).doesNotContain("35");

    }

    @Test
    @DisplayName("직접 검토 모드의 완료 후보도 확정할 수 있고 단순 확정을 직접 값 수정으로 오인하지 않는다")
    void manualOrderedReviewDoesNotLabelConfirmationAsEdit() {
        Run run = run(1, AnalysisReviewMode.MANUAL);
        var first = claim();
        List<UUID> selected = tx.execute(status -> {
            var job = jobs.findById(first.analysisJobId()).orElseThrow();
            var subject = discovery(job, "에르웬", null, true);
            var stat = addStat(job, subject.getProvisionalSubjectKey(), null, 35, CharacterFactOperation.ADD);
            return List.of(subject.getId(), stat.getId());
        });
        complete(first);
        Long owner = tx.execute(status -> entities.find(Work.class, run.workId()).getMember().getId());
        UUID batch = tx.execute(status -> jobs.findById(first.analysisJobId()).orElseThrow().getBatch().getId());
        var request = new SettingCandidateGroupConfirmRequest(batch, selected.stream().map(id ->
                new SettingCandidateGroupConfirmDecision(id, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, null, false)).toList());
        assertThat(characterReview.confirmSettingCandidateGroup(owner, run.workId(), request).recomparisonRequired()).isFalse();
        tx.executeWithoutResult(status -> {
            var candidate = candidates.findById(selected.get(1)).orElseThrow();
            assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
            assertThat(candidate.isUserModified()).isFalse();
            assertThat(jobs.findById(first.analysisJobId()).orElseThrow().getJournalStatus()).isEqualTo(AnalysisJournalStatus.SEALED);
        });
    }

    static Stream<Arguments> lateCharacterCases() {
        return Stream.of("latest", "removed", "manual", "empty", "past", "history", "remove")
                .flatMap(scenario -> Stream.of(Arguments.of(scenario, false), Arguments.of(scenario, true)));
    }

    @Test
    @DisplayName("기존에 무효화된 회차의 손대지 않은 후보는 현재 설정으로 되살리지 않는다")
    void invalidatedOrderedCandidatesRequireExplicitRecovery() {
        Run run = run(1, AnalysisReviewMode.MANUAL);
        var payload = claim();
        List<UUID> candidateIds = tx.execute(status -> {
            AnalysisJob job = jobs.findById(payload.analysisJobId()).orElseThrow();
            SettingCandidate discovered = discovery(job, "에르웬", null, true);
            SettingCandidate stat = addStat(job, discovered.getProvisionalSubjectKey(), null, 35,
                    CharacterFactOperation.ADD);
            return List.of(discovered.getId(), stat.getId());
        });
        complete(payload);
        Long owner = tx.execute(status -> entities.find(Work.class, run.workId()).getMember().getId());
        UUID settingId = tx.execute(status -> {
            WorldSetting setting = WorldSetting.create(entities.find(Work.class, run.workId()),
                    WorldSettingCategory.RACE, "바바리안", "서식지", "북부");
            entities.persist(setting);
            return setting.getId();
        });
        tx.executeWithoutResult(status -> states.invalidateRunsForEpisodeChangeForUpdate(
                run.workId(), jobs.findById(payload.analysisJobId()).orElseThrow().getEpisode().getId(), 1, "기존 원문 변경으로 무효화된 기록"));

        UUID batch = tx.execute(status -> jobs.findById(payload.analysisJobId()).orElseThrow().getBatch().getId());
        var request = new SettingCandidateGroupConfirmRequest(batch, candidateIds.stream()
                .map(id -> new SettingCandidateGroupConfirmDecision(
                        id, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, null, false))
                .toList());
        var result = characterReview.confirmSettingCandidateGroup(owner, run.workId(), request);

        assertThat(result.recomparisonRequired()).isTrue();
        tx.executeWithoutResult(status -> {
            assertThat(jobs.findById(payload.analysisJobId()).orElseThrow().getJournalStatus())
                    .isEqualTo(AnalysisJournalStatus.INVALIDATED);
            assertThat(entities.createQuery("select count(c) from WorkCharacter c where c.work.id = :work", Long.class)
                    .setParameter("work", run.workId()).getSingleResult()).isZero();
            assertThat(candidateIds).allSatisfy(id -> assertThat(candidates.findById(id).orElseThrow().isPendingReview())
                    .isTrue());
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("기존에 무효화된 회차의 손대지 않은 세계관 후보는 다시 비교한다")
    void invalidatedOrderedWorldCandidatesRequireExplicitRecovery(boolean group) {
        Run run = run(1, AnalysisReviewMode.MANUAL);
        var payload = claim();
        UUID candidateId = tx.execute(status -> {
            AnalysisJob job = jobs.findById(payload.analysisJobId()).orElseThrow();
            addWorld(job);
            return entities.createQuery(
                            "select c.id from WorldSettingCandidate c where c.analysisJob.id = :job",
                            UUID.class
                    )
                    .setParameter("job", job.getId())
                    .getSingleResult();
        });
        complete(payload);
        Long owner = tx.execute(status -> entities.find(Work.class, run.workId()).getMember().getId());
        UUID settingId = tx.execute(status -> {
            WorldSetting setting = WorldSetting.create(entities.find(Work.class, run.workId()),
                    WorldSettingCategory.RACE, "바바리안", "서식지", "북부");
            entities.persist(setting);
            return setting.getId();
        });
        tx.executeWithoutResult(status -> states.invalidateRunsForEpisodeChangeForUpdate(
                run.workId(), jobs.findById(payload.analysisJobId()).orElseThrow().getEpisode().getId(), 1, "기존 원문 변경으로 무효화된 기록"));

        UUID batch = tx.execute(status -> jobs.findById(payload.analysisJobId()).orElseThrow().getBatch().getId());
        if (group) {
            var request = new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateGroupConfirmRequest(
                    batch,
                    List.of(new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateGroupConfirmRequest.Decision(
                            candidateId, WorldSettingOperation.ADD, WorldSettingCategory.RACE,
                            "엘프", null, "서식지", "북부", false, null
                    ))
            );
            assertThat(worldApplication.confirmCandidateGroup(owner, run.workId(), request).recomparisonRequired())
                    .isTrue();
        } else {
            var request = new WorldSettingCandidateConfirmRequest(
                    WorldSettingOperation.ADD, WorldSettingCategory.RACE,
                    "엘프", null, "서식지", "북부", false, null
            );
            assertThat(worldApplication.confirmCandidate(owner, run.workId(), candidateId, request)
                    .recomparisonRequired()).isTrue();
        }

        tx.executeWithoutResult(status -> {
            WorldSettingCandidate candidate = entities.find(WorldSettingCandidate.class, candidateId);
            assertThat(candidate.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.PENDING_REVIEW);
            assertThat(candidate.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.RECOMPARISON_REQUIRED);
            assertThat(jobs.findById(payload.analysisJobId()).orElseThrow().getJournalStatus())
                    .isEqualTo(AnalysisJournalStatus.INVALIDATED);
            assertThat(entities.createQuery(
                            "select count(w) from WorldSetting w where w.work.id = :work", Long.class)
                    .setParameter("work", run.workId())
                    .getSingleResult()).isEqualTo(1L);
        });
    }

    @Test
    @DisplayName("완료된 회차의 캐릭터 묶음 확정은 여러 설정을 반영해도 현재 설정 버전을 한 번만 올린다")
    void lateCharacterGroupIncrementsSnapshotVersionOnce() {
        Run run = run(1, AnalysisReviewMode.MANUAL);
        var payload = claim();
        List<UUID> candidateIds = tx.execute(status -> {
            AnalysisJob job = jobs.findById(payload.analysisJobId()).orElseThrow();
            CharacterSettingSchema strength = CharacterSettingSchema.create(job.getWork(), "stats.strength", null,
                    "근력", CharacterFactType.STAT, SettingValueType.NUMBER,
                    CharacterSettingValueSemantics.BASE_VALUE, CharacterSettingMergePolicy.REPLACE,
                    JSON.arrayNode(), CharacterSettingSchemaSource.SYSTEM_SEED, true);
            entities.persist(strength);
            SettingCandidate discovered = discovery(job, "에르웬", null, true);
            SettingCandidate mental = addStat(job, discovered.getProvisionalSubjectKey(), null,
                    "stats.mental", 35, CharacterFactOperation.ADD);
            SettingCandidate strengthCandidate = addStat(job, discovered.getProvisionalSubjectKey(), null,
                    "stats.strength", 25, CharacterFactOperation.ADD);
            return List.of(discovered.getId(), mental.getId(), strengthCandidate.getId());
        });
        complete(payload);
        Long owner = tx.execute(status -> entities.find(Work.class, run.workId()).getMember().getId());
        UUID batch = tx.execute(status -> jobs.findById(payload.analysisJobId()).orElseThrow().getBatch().getId());
        var request = new SettingCandidateGroupConfirmRequest(batch, candidateIds.stream()
                .map(id -> new SettingCandidateGroupConfirmDecision(
                        id, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, null, false))
                .toList());

        assertThat(characterReview.confirmSettingCandidateGroup(owner, run.workId(), request).recomparisonRequired())
                .isFalse();

        tx.executeWithoutResult(status -> {
            WorkCharacter character = entities.createQuery(
                            "select c from WorkCharacter c where c.work.id = :work", WorkCharacter.class)
                    .setParameter("work", run.workId()).getSingleResult();
            assertThat(character.getSnapshotVersion()).isEqualTo(1L);
            String input = character.getStatsJson().toString();
            assertThat(input).contains("mental", "35", "strength", "25");
        });
    }

    @Test
    @DisplayName("같은 캐릭터 항목의 묶음 확정은 현재값 하나를 선택한 뒤에만 성공한다")
    void lateCharacterGroupProjectsEarlierSlotDecisions() {
        Run run = run(1, AnalysisReviewMode.MANUAL);
        var payload = claim();
        List<UUID> candidateIds = tx.execute(status -> {
            AnalysisJob job = jobs.findById(payload.analysisJobId()).orElseThrow();
            SettingCandidate discovered = discovery(job, "에르웬", null, true);
            SettingCandidate first = addStat(job, discovered.getProvisionalSubjectKey(), null,
                    "stats.mental", 35, CharacterFactOperation.ADD);
            SettingCandidate second = addStat(job, discovered.getProvisionalSubjectKey(), null,
                    "stats.mental", 36, CharacterFactOperation.ADD);
            return List.of(discovered.getId(), first.getId(), second.getId());
        });
        complete(payload);
        Long owner = tx.execute(status -> entities.find(Work.class, run.workId()).getMember().getId());
        UUID batch = tx.execute(status -> jobs.findById(payload.analysisJobId()).orElseThrow().getBatch().getId());
        var request = new SettingCandidateGroupConfirmRequest(batch, candidateIds.stream()
                .map(id -> new SettingCandidateGroupConfirmDecision(
                        id, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, null, false))
                .toList());

        assertThatThrownBy(() -> characterReview.confirmSettingCandidateGroup(owner, run.workId(), request))
                .isInstanceOfSatisfying(AppException.class, error -> assertThat(error.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_CURRENT_SELECTION_CONFLICT));
        var chosen = new SettingCandidateGroupConfirmRequest(batch, candidateIds.stream()
                .map(id -> new SettingCandidateGroupConfirmDecision(id, id.equals(candidateIds.get(2))
                        ? CharacterFactConfirmApplicationMode.HISTORY_ONLY : CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, null, false)).toList());
        assertThat(characterReview.confirmSettingCandidateGroup(owner, run.workId(), chosen).recomparisonRequired()).isFalse();

        tx.executeWithoutResult(status -> {
            WorkCharacter character = entities.createQuery(
                            "select c from WorkCharacter c where c.work.id = :work", WorkCharacter.class)
                    .setParameter("work", run.workId())
                    .getSingleResult();
            assertThat(character.getSnapshotVersion()).isEqualTo(1L);
            assertThat(character.getStatsJson().toString()).contains("mental", "35").doesNotContain("36");
            assertThat(candidates.findById(candidateIds.get(1)).orElseThrow().getConfirmedApplicationMode())
                    .isEqualTo(CharacterFactConfirmApplicationMode.APPLY_PROPOSAL);
            assertThat(candidates.findById(candidateIds.get(2)).orElseThrow().getConfirmedApplicationMode())
                    .isEqualTo(CharacterFactConfirmApplicationMode.HISTORY_ONLY);
            assertThat(entities.createQuery("select count(f) from CharacterFact f", Long.class).getSingleResult())
                    .isEqualTo(2L);
        });
    }

    @ParameterizedTest(name = "{0}, group={1}")
    @MethodSource("lateWorldCases")
    @DisplayName("세계관 늦은 확정은 같은 경로의 최신값·수동 수정·제거를 보호하고 새 경로만 추가한다")
    void lateWorldReviewProtectsCurrentAndKeepsCompletedAnalysis(String scenario, boolean group) {
        Run run = run(2);
        var first = claim();
        UUID pending = tx.execute(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            WorldSettingCandidate candidate = WorldSettingCandidate.create(job.getWork(), job.getEpisode(), job,
                    WorldSettingCategory.RACE, "엘프", "서식지", "남부", JSON.arrayNode(), BigDecimal.ONE, null);
            entities.persist(candidate);
            String key = "provisional-world:" + candidate.getId();
            candidate.resolveOrderedSubject(WorldSettingSubjectResolutionType.NEW, key, "엘프", JSON.arrayNode(), JSON.arrayNode().add(key));
            WorldSettingComparisonBatch comparison = WorldSettingComparisonBatch.createOrdered(job.getWork(), job.getEpisode(), job,
                    WorldSettingCategory.RACE, null, WorldSettingSubjectResolutionType.NEW, key, "엘프", JSON.arrayNode(), JSON.arrayNode().add(key), 1);
            entities.persist(comparison);
            candidate.startComparison(comparison, "C1");
            comparison.recordContext(JSON.objectNode().putObject("targets").putObject(key));
            candidate.failComparison(AnalysisFailureCode.LLM_RESPONSE_PARSE_ERROR, "직접 확인 필요");
            comparison.fail(AnalysisFailureCode.LLM_RESPONSE_PARSE_ERROR, "직접 확인 필요");
            return candidate.getId();
        });
        complete(first);
        var second = claim();
        tx.executeWithoutResult(status -> addWorld(jobs.findById(second.analysisJobId()).orElseThrow()));
        complete(second);
        Long owner = tx.execute(status -> entities.find(Work.class, run.workId()).getMember().getId());
        UUID batch = tx.execute(status -> jobs.findById(first.analysisJobId()).orElseThrow().getBatch().getId());
        UUID target = tx.execute(status -> {
            WorldSetting current = entities.createQuery("select w from WorldSetting w where w.work.id = :work", WorldSetting.class)
                    .setParameter("work", run.workId()).getSingleResult();
            if (scenario.equals("removed")) current.replaceConfirmedProperties(List.of(new WorldSetting.Property(null, "외형", "길쭉한 귀")));
            if (scenario.equals("manual")) { current.applyProperty(null, "서식지", "작가가 고른 숲"); current.protectManualProperty(null, "서식지"); }
            return current.getId();
        });
        String scope = scenario.equals("newScope") ? "북쪽 무리" : null;
        String name = scenario.equals("newProperty") || scenario.equals("missingUpdate") ? "전투특징" : "서식지";
        WorldSettingOperation operation = scenario.equals("missingUpdate")
                ? WorldSettingOperation.UPDATE : WorldSettingOperation.ADD;
        worldApplication.updateCandidateDecisions(owner, run.workId(), new WorldSettingCandidateDecisionUpdateRequest(batch,
                List.of(new WorldSettingCandidateDecisionUpdateItem(pending, operation, WorldSettingCategory.RACE,
                        "엘프", scope, name, "남부", "직접 확인"))));
        if (group) {
            var request = new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateGroupConfirmRequest(batch,
                    List.of(new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateGroupConfirmRequest.Decision(
                            pending, operation, WorldSettingCategory.RACE, "엘프", scope, name, "남부", true, "직접 확인")));
            assertThat(worldApplication.confirmCandidateGroup(owner, run.workId(), request).recomparisonRequired()).isFalse();
            nextBatch(run.workId(), 3);
            assertThat(worldApplication.confirmCandidateGroup(owner, run.workId(), request).recomparisonRequired()).isFalse();
        } else {
            var request = new WorldSettingCandidateConfirmRequest(operation, WorldSettingCategory.RACE,
                    "엘프", scope, name, "남부", true, "직접 확인");
            assertThat(worldApplication.confirmCandidate(owner, run.workId(), pending, request).recomparisonRequired()).isFalse();
            nextBatch(run.workId(), 3);
            assertThat(worldApplication.confirmCandidate(owner, run.workId(), pending, request).recomparisonRequired()).isFalse();
        }
        boolean historical = !scenario.startsWith("new");
        tx.executeWithoutResult(status -> {
            WorldSettingCandidate candidate = entities.find(WorldSettingCandidate.class, pending);
            assertThat(candidate.isHistoryOnly()).isEqualTo(historical);
            assertThat(candidate.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED);
            assertThat(entities.find(WorldSetting.class, target).getPropertyValue(scope, name)).isEqualTo(
                    !historical ? "남부"
                            : scenario.equals("removed") || scenario.equals("missingUpdate") ? null
                            : scenario.equals("manual") ? "작가가 고른 숲" : "북부");
            assertThat(jobs.findById(second.analysisJobId()).orElseThrow().getJournalStatus()).isEqualTo(AnalysisJournalStatus.SEALED);
        });
        String nextProperties = input(claim()).path("worldSettings").elements().next().path("propertiesJson").toString();
        if (historical) assertThat(nextProperties).doesNotContain("남부");
        else assertThat(nextProperties).contains("남부");
    }

    static Stream<Arguments> lateWorldCases() {
        return Stream.of("latest", "removed", "manual", "newProperty", "newScope", "missingUpdate")
                .flatMap(scenario -> Stream.of(Arguments.of(scenario, false), Arguments.of(scenario, true)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("늦은 세계관 후보의 대상을 새 이름으로 바꾸면 원래 대상이 아닌 새 대상에 반영한다")
    void lateWorldEditedTargetUsesSelectedIdentity(boolean group) {
        Run run = run(1, AnalysisReviewMode.MANUAL);
        var payload = claim();
        UUID candidateId = tx.execute(status -> {
            AnalysisJob job = jobs.findById(payload.analysisJobId()).orElseThrow();
            addWorld(job);
            return entities.createQuery(
                            "select c.id from WorldSettingCandidate c where c.analysisJob.id = :job",
                            UUID.class
                    )
                    .setParameter("job", job.getId())
                    .getSingleResult();
        });
        complete(payload);
        Long owner = tx.execute(status -> entities.find(Work.class, run.workId()).getMember().getId());
        UUID originalId = tx.execute(status -> {
            WorldSetting original = WorldSetting.create(entities.find(Work.class, run.workId()),
                    WorldSettingCategory.RACE, "엘프", "서식지", "북부");
            entities.persist(original);
            ReflectionTestUtils.setField(
                    entities.find(WorldSettingCandidate.class, candidateId),
                    "targetWorldSetting",
                    original
            );
            return original.getId();
        });
        UUID batch = tx.execute(status -> jobs.findById(payload.analysisJobId()).orElseThrow().getBatch().getId());
        worldApplication.updateCandidateDecisions(owner, run.workId(), new WorldSettingCandidateDecisionUpdateRequest(
                batch,
                List.of(new WorldSettingCandidateDecisionUpdateItem(
                        candidateId, WorldSettingOperation.ADD, WorldSettingCategory.RACE,
                        "하이엘프", null, "서식지", "남부", "새 대상으로 직접 확인"
                ))
        ));

        if (group) {
            var request = new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateGroupConfirmRequest(
                    batch,
                    List.of(new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateGroupConfirmRequest.Decision(
                            candidateId, WorldSettingOperation.ADD, WorldSettingCategory.RACE,
                            "하이엘프", null, "서식지", "남부", false, "새 대상으로 직접 확인"
                    ))
            );
            assertThat(worldApplication.confirmCandidateGroup(owner, run.workId(), request).recomparisonRequired())
                    .isFalse();
        } else {
            var request = new WorldSettingCandidateConfirmRequest(
                    WorldSettingOperation.ADD, WorldSettingCategory.RACE,
                    "하이엘프", null, "서식지", "남부", false, "새 대상으로 직접 확인"
            );
            assertThat(worldApplication.confirmCandidate(owner, run.workId(), candidateId, request)
                    .recomparisonRequired()).isFalse();
        }

        tx.executeWithoutResult(status -> {
            WorldSetting original = entities.find(WorldSetting.class, originalId);
            WorldSetting selected = entities.createQuery(
                            "select w from WorldSetting w where w.work.id = :work and w.subjectName = :name",
                            WorldSetting.class
                    )
                    .setParameter("work", run.workId())
                    .setParameter("name", "하이엘프")
                    .getSingleResult();
            assertThat(original.getPropertyValue(null, "서식지")).isEqualTo("북부");
            assertThat(selected.getPropertyValue(null, "서식지")).isEqualTo("남부");
            WorldSettingCandidate candidate = entities.find(WorldSettingCandidate.class, candidateId);
            assertThat(candidate.getTargetWorldSetting().getId()).isEqualTo(selected.getId());
            assertThat(candidate.isHistoryOnly()).isFalse();
        });
    }

    private SettingCandidate addUnresolvedReference(AnalysisJob job) {
        SettingCandidate candidate = SettingCandidate.create(job.getWork(), job.getEpisode(), null, job,
                SettingEntityType.CHARACTER, "미상", "stats.mental", "99", SettingValueType.NUMBER,
                JSON.objectNode().put("value", 99), evidence("정신 수치는 99였다."),
                BigDecimal.ONE, null);
        entities.persist(candidate);
        return candidate;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("저장 직전 제안 오류는 그 항목과 의존 항목만 보류하고 정상 설정 저장과 다음 회차를 이어간다")
    void invalidFinalProposalDoesNotRollBackUnrelatedSettings(boolean invalidValueType) {
        Run run = run(2);
        WorkerAnalysisJobPayload first = claim();
        List<UUID> ids = tx.execute(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            SettingCandidate badSubject = discovery(job, "에르웬", null, true);
            SettingCandidate bad = addStat(job, badSubject.getProvisionalSubjectKey(), null, 35, CharacterFactOperation.ADD);
            // Simulate a persisted comparison that passes preparation but is invalid for actual promotion.
            if (invalidValueType) {
                ReflectionTestUtils.setField(bad, "proposedValueJson", JSON.objectNode().put("value", "숫자 아님"));
            } else {
                ReflectionTestUtils.setField(bad, "proposedFactValue", " ");
            }
            SettingCandidate dependent = addStat(job, badSubject.getProvisionalSubjectKey(), null, 36, CharacterFactOperation.UPDATE);
            SettingCandidate goodSubject = discovery(job, "비요른", null, true);
            SettingCandidate good = addStat(job, goodSubject.getProvisionalSubjectKey(), null, 50, CharacterFactOperation.ADD);
            ReflectionTestUtils.setField(good, "entityName", "비요른");
            addWorld(job);
            return List.of(bad.getId(), dependent.getId(), good.getId(), badSubject.getId(), goodSubject.getId());
        });
        complete(first);
        List<UUID> actualIds = tx.execute(status -> {
            SettingCandidate bad = candidates.findById(ids.get(0)).orElseThrow();
            assertThat(bad.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
            assertThat(bad.getAutomaticReviewHoldReason()).isEqualTo(
                    org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.SETTING_VALUE_CONFIRMATION_REQUIRED);
            SettingCandidate dependent = candidates.findById(ids.get(1)).orElseThrow();
            assertThat(dependent.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
            assertThat(dependent.getAutomaticReviewHoldReason()).isNotNull();
            assertThat(candidates.findById(ids.get(2)).orElseThrow().getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
            assertThat(entities.createQuery("select count(f) from CharacterFact f", Long.class).getSingleResult()).isEqualTo(1);
            assertThat(entities.createQuery("select count(w) from WorldSetting w", Long.class).getSingleResult()).isEqualTo(1);
            AnalysisJob completed = jobs.findById(first.analysisJobId()).orElseThrow();
            assertThat(completed.getStatus()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
            assertThat(completed.getJournalStatus()).isEqualTo(AnalysisJournalStatus.SEALED);
            assertThat(completed.getAutomaticAppliedAt()).isNotNull();
            return List.of(candidates.findById(ids.get(3)).orElseThrow().getMatchedCharacterId(),
                    candidates.findById(ids.get(4)).orElseThrow().getMatchedCharacterId());
        });
        WorkerAnalysisJobPayload second = claim();
        assertThat(second.analysisJobId()).isEqualTo(run.jobs().get(1));
        JsonNode next = input(second).path("characters");
        assertThat(next.path("character:" + actualIds.get(0)).path("slots").isEmpty()).isTrue();
        assertThat(next.path("character:" + actualIds.get(1)).path("slots").path("STAT:stats.mental").path("factValue").asText())
                .isEqualTo("50");
    }

    @Test
    @DisplayName("분석 성공과 journal 봉인만으로 다음 회차를 열지 않고 자동 저장 완료 표시까지 요구한다")
    void nextClaimWaitsForAppliedMarker() {
        run(2);
        WorkerAnalysisJobPayload first = claim();
        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            states.seal(job);
            job.succeed("{}", 0, 0);
        });
        assertThat(worker.claimAnalysisJob(request())).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(AnalysisReviewMode.class)
    @DisplayName("Worker lease 확인은 자동 누적 고정 입력을 다시 hash하지 않아 heartbeat·progress·fail이 통과하고 입력을 읽는 주체 대기 목록·완료·재개가 변조를 거절한다. 수동 누적은 lease에서 그대로 거절한다")
    void leaseCallsSkipOnlyAutomaticInputHash(AnalysisReviewMode mode) {
        boolean automatic = mode == AnalysisReviewMode.AUTOMATIC;
        Run run = run(2, mode);
        WorkerAnalysisJobPayload first = claim();
        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            ObjectNode tampered = (ObjectNode) (automatic ? job.getAutomaticInputState() : job.getRunBaseState()).deepCopy();
            tampered.put("tamperedForTest", "저장 입력 변조");
            ReflectionTestUtils.setField(job, automatic ? "automaticInputState" : "runBaseState", tampered);
        });
        var progress = new org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobProgressRequest(
                "비교", org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.ANALYZING, null);
        if (!automatic) {
            assertRunStateConflict(() -> worker.heartbeatAnalysisJob(first.analysisJobId(), first.leaseToken()));
            assertRunStateConflict(() -> worker.updateProgress(first.analysisJobId(), first.leaseToken(), progress));
            return;
        }
        worker.heartbeatAnalysisJob(first.analysisJobId(), first.leaseToken());
        worker.updateProgress(first.analysisJobId(), first.leaseToken(), progress);
        // 고정 references를 그대로 싣는 세계관 주체 대기 목록은 lease 대신 직접 hash를 확인한다.
        assertRunStateConflict(() -> worldWorker.getPendingWorldSettingSubjectResolutions(first.analysisJobId(), first.leaseToken()));
        assertRunStateConflict(() -> complete(first));
        assertRunStateConflict(() -> tx.executeWithoutResult(status ->
                states.getProjectedState(jobs.findById(first.analysisJobId()).orElseThrow())));
        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.RUNNING);
            assertThat(job.getJournalStatus()).isEqualTo(AnalysisJournalStatus.PENDING);
            assertThat(job.getAutomaticAppliedAt()).isNull();
        });
        worker.failAnalysisJob(first.analysisJobId(), first.leaseToken(),
                new org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobFailRequest(
                        AnalysisFailureCode.UNEXPECTED_ERROR, "저장 연결 일시 실패"));
        Long owner = tx.execute(status -> entities.find(Work.class, run.workId()).getMember().getId());
        assertRunStateConflict(() -> publicJobs.retryFailedAnalysisJob(owner, run.workId(), first.analysisJobId()));
        assertThat(tx.<AnalysisJobStatus>execute(status -> jobs.findById(first.analysisJobId()).orElseThrow().getStatus()))
                .isEqualTo(AnalysisJobStatus.FAILED);
    }

    @ParameterizedTest
    @EnumSource(AnalysisReviewMode.class)
    @DisplayName("대상별 읽기는 실제 claim·비교·완료를 거친 두 회차에서 전체 입력·투영 상태의 같은 경로를 읽은 값과 같다")
    void perSubjectReadsMatchWholeStateAcrossEpisodes(AnalysisReviewMode mode) {
        run(2, mode);
        WorkerAnalysisJobPayload first = claim();
        String provisional = tx.execute(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            SettingCandidate anchor = discovery(job, "에르웬", null, true);
            SettingCandidate alias = SettingCandidate.createCharacterDiscovery(job.getWork(), job.getEpisode(), null, job,
                    "에르웬 미샤", "에르웬", null, SettingCandidateMatchStatus.MATCHED,
                    evidence("에르웬의 본명은 에르웬 미샤다."), BigDecimal.ONE, null);
            ReflectionTestUtils.setField(alias, "provisionalSubjectKey", anchor.getProvisionalSubjectKey());
            entities.persist(alias);
            addStat(job, anchor.getProvisionalSubjectKey(), null, 35, CharacterFactOperation.ADD);
            addWorld(job);
            addUnresolvedReference(job);
            return anchor.getProvisionalSubjectKey();
        });
        // 첫 회차: claim 입력은 비어 있고 이 Job의 변경(임시 인물·세계관·참고)이 투영에 있다.
        tx.executeWithoutResult(status -> assertThat(org.monitoring.catchholebackend.support.AnalysisRunStateEquivalence
                .assertMatchesWholeState(states, jobs.findById(first.analysisJobId()).orElseThrow())).isEqualTo(2));
        complete(first);

        WorkerAnalysisJobPayload second = claim();
        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(second.analysisJobId()).orElseThrow();
            JsonNode input = states.getInputState(job);
            if (mode == AnalysisReviewMode.AUTOMATIC) {
                // 자동: claim 때 읽은 실제 인물(별칭 포함)·세계관과 앞 회차의 미확정 참고
                assertThat(input.path("characters").toString()).contains("actualCharacterId", "에르웬 미샤");
                assertThat(input.path("references").isEmpty()).isFalse();
            } else {
                // 수동: S0와 앞 회차 변경을 replay한 임시 인물
                assertThat(input.path("characters").has(provisional)).isTrue();
            }
            assertThat(input.path("worldSettings").isEmpty()).isFalse();
            assertThat(org.monitoring.catchholebackend.support.AnalysisRunStateEquivalence.assertMatchesWholeState(states, job))
                    .isEqualTo(2);
            // #387 format 2의 live 읽기(대상별·claim 현재값)가 실제 claim이 고정한 입력과 같다. 앞 회차 자동 반영이 만든 인물·세계관이다.
            if (mode == AnalysisReviewMode.AUTOMATIC) assertThat(org.monitoring.catchholebackend.support.AnalysisLiveStateEquivalence
                    .assertLiveMatchesClaimInput(liveState, stateJournal, states, job)).isEqualTo(2);
            String ref = input.path("characters").fieldNames().next();
            UUID actualId = mode == AnalysisReviewMode.AUTOMATIC ? UUID.fromString(input.path("characters").path(ref).path("actualCharacterId").asText()) : null;
            addStat(job, actualId == null ? ref : null, actualId, 36, CharacterFactOperation.UPDATE);
            assertThat(org.monitoring.catchholebackend.support.AnalysisRunStateEquivalence.assertMatchesWholeState(states, job))
                    .isEqualTo(2);
            assertThat(states.getProjectedTarget(job, "characters", ref).at("/slots/STAT:stats.mental/factValue").asText()).isEqualTo("36");
            assertThat(states.getInputTarget(job, "characters", ref).at("/slots/STAT:stats.mental/factValue").asText()).isEqualTo("35");
        });
    }

    private static void assertRunStateConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AppException.class, error ->
                assertThat(error.getResultCode().getCode()).isEqualTo("ANALYSIS_RUN_STATE_CONFLICT"));
    }

    @Test
    @DisplayName("캐릭터 저장 뒤 세계관 반영이 실패하면 전체를 롤백하고 같은 완료 요청 재시도는 중복 없이 저장한다")
    void rollbackAndRetryDoNotDuplicateCharacter() {
        run(2);
        WorkerAnalysisJobPayload first = claim();
        UUID candidateId = tx.execute(status -> discovery(jobs.findById(first.analysisJobId()).orElseThrow(), "에르웬", null, true).getId());
        doThrow(new IllegalStateException("세계관 저장 실패 재현")).when(worldApplication).applyAutomatically(any());
        assertThatThrownBy(() -> complete(first)).isInstanceOf(IllegalStateException.class);
        assertThat(tx.<Long>execute(status -> entities.createQuery("select count(c) from WorkCharacter c", Long.class).getSingleResult())).isZero();
        assertThat(tx.<SettingCandidateReviewStatus>execute(status -> candidates.findById(candidateId).orElseThrow().getReviewStatus()))
                .isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
        assertThat(worker.claimAnalysisJob(request())).isEmpty();
        doCallRealMethod().when(worldApplication).applyAutomatically(any());
        complete(first);
        assertThat(tx.<Long>execute(status -> entities.createQuery("select count(c) from WorkCharacter c", Long.class).getSingleResult())).isEqualTo(1);
        assertThat(tx.<Boolean>execute(status -> candidates.findById(candidateId).orElseThrow().isReviewedAutomatically())).isTrue();
        assertThatThrownBy(() -> complete(first)).isInstanceOf(RuntimeException.class);
        assertThat(tx.<Long>execute(status -> entities.createQuery("select count(c) from WorkCharacter c", Long.class).getSingleResult())).isEqualTo(1);
        assertThat(worker.claimAnalysisJob(request())).isPresent();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("새 인물의 끝난 상태는 이력만 저장하고 저장 재시도와 다음 회차에도 현재 상태로 추가하지 않는다")
    void newCharacterPastStatusOnlyCreatesHistoryIncludingAfterCompletionRetry(boolean failFirstCompletion) {
        Run run = run(2);
        WorkerAnalysisJobPayload first = claim();
        UUID statusId = tx.execute(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            entities.persist(CharacterSettingSchema.create(job.getWork(), "statuses.status", "status.*", "상태",
                    CharacterFactType.STATUS, SettingValueType.JSON, CharacterSettingValueSemantics.BASE_VALUE,
                    CharacterSettingMergePolicy.UPSERT_BY_NAME, JSON.arrayNode(), CharacterSettingSchemaSource.SYSTEM_SEED, true));
            SettingCandidate discovered = discovery(job, "데이비스", null, true);
            ObjectNode value = JSON.objectNode().put("name", "기절").put("active", false)
                    .put("description", "기절했다가 치료를 받고 회복했다.");
            SettingCandidate candidate = SettingCandidate.create(job.getWork(), job.getEpisode(), null, job,
                    SettingEntityType.CHARACTER, "데이비스", "데이비스", null, SettingCandidateMatchStatus.UNRESOLVED,
                    "status.기절", "기절했다가 회복함", SettingValueType.JSON, value,
                    evidence("데이비스는 기절했지만 치료 후 깨어났다."),
                    BigDecimal.ONE, null);
            ReflectionTestUtils.setField(candidate, "provisionalSubjectKey", discovered.getProvisionalSubjectKey());
            entities.persist(candidate);
            entities.flush();
            characterStates.prepareProvisionalCandidates(job);
            CharacterFactComparisonBatch batch = CharacterFactComparisonBatch.createProvisional(
                    job.getWork(), job.getEpisode(), job, discovered.getProvisionalSubjectKey(), CharacterFactType.STATUS, 1);
            batch.recordAnalysisContext(JSON.objectNode().set("slots", JSON.arrayNode()));
            entities.persist(batch);
            candidate.startComparison(batch, "C1");
            candidate.recordComparisonContext(batch.getBaseSnapshotVersion(), job.getInputStateHash());
            candidate.completeComparison(CharacterFactOperation.HISTORY_ONLY, null, null, null, null,
                    JSON.arrayNode(), CharacterFactTemporalScope.PAST, "이미 끝난 기절은 과거 이력으로 남깁니다.",
                    JSON.objectNode(), LocalDateTime.now(), "status.기절", JSON.arrayNode());
            batch.complete("d".repeat(64), JSON.objectNode());
            characterStates.recordDecision(job, candidate,
                    new CharacterSnapshotEntry(new CharacterSnapshotSlot(CharacterFactType.STATUS, "status.기절"),
                            candidate.getAttributeValue(), value, true), List.of(), List.of());
            job.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED);
            return candidate.getId();
        });
        if (failFirstCompletion) {
            doThrow(new IllegalStateException("세계관 저장 실패 재현")).when(worldApplication).applyAutomatically(any());
            assertThatThrownBy(() -> complete(first)).isInstanceOf(IllegalStateException.class);
            tx.executeWithoutResult(status -> {
                assertThat(entities.createQuery("select count(c) from WorkCharacter c", Long.class).getSingleResult()).isZero();
                assertThat(entities.createQuery("select count(f) from CharacterFact f", Long.class).getSingleResult()).isZero();
                SettingCandidate candidate = candidates.findById(statusId).orElseThrow();
                assertThat(candidate.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.COMPLETED);
                assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
                assertThat(candidate.getAnalysisJob().getCheckpointStage()).isEqualTo(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED);
            });
            assertThat(worker.claimAnalysisJob(request())).isEmpty();
            doCallRealMethod().when(worldApplication).applyAutomatically(any());
        }
        complete(first);
        UUID characterId = tx.execute(status -> {
            SettingCandidate candidate = candidates.findById(statusId).orElseThrow();
            assertThat(candidate.getSuggestedOperation()).isEqualTo(CharacterFactOperation.HISTORY_ONLY);
            assertThat(candidate.getTemporalScope()).isEqualTo(CharacterFactTemporalScope.PAST);
            assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
            assertThat(candidate.isReviewedAutomatically()).isTrue();
            assertThat(candidate.getConfirmedApplicationMode()).isEqualTo(CharacterFactConfirmApplicationMode.APPLY_PROPOSAL);
            assertThat(candidate.getAnalysisJob().getStatus()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
            assertThat(candidate.getAnalysisJob().getAutomaticAppliedAt()).isNotNull();
            List<CharacterFact> facts = entities.createQuery("select f from CharacterFact f", CharacterFact.class).getResultList();
            assertThat(facts).singleElement().satisfies(fact -> {
                assertThat(fact.getSettingCandidate().getId()).isEqualTo(statusId);
                assertThat(fact.getFactType()).isEqualTo(CharacterFactType.STATUS);
                assertThat(fact.getFactKey()).isEqualTo("status.기절");
                assertThat(fact.getValueJson().path("active").asBoolean()).isFalse();
            });
            assertThat(entities.createQuery("select count(c) from WorkCharacter c", Long.class).getSingleResult()).isEqualTo(1);
            return candidate.getMatchedCharacterId();
        });
        assertThat(characterId).isNotNull();
        assertThatThrownBy(() -> complete(first)).isInstanceOf(RuntimeException.class);
        assertThat(tx.<Long>execute(status -> entities.createQuery("select count(f) from CharacterFact f", Long.class).getSingleResult())).isEqualTo(1);
        WorkerAnalysisJobPayload second = claim();
        assertThat(second.analysisJobId()).isEqualTo(run.jobs().get(1));
        assertThat(second.knownCharacters()).singleElement().satisfies(character -> {
            assertThat(character.characterId()).isEqualTo(characterId);
            assertThat(character.activeStatuses()).isEmpty();
        });
        assertThat(input(second).path("characters").path("character:" + characterId).path("slots").isEmpty()).isTrue();
        complete(second);
    }

    @Test
    @DisplayName("작가가 직접 등록한 인물과 세계관은 자동 분석 문맥에서도 HUMAN 출처로 구분한다")
    void existingHumanSettingsKeepHumanProvenance() {
        Run run = run(1);
        tx.executeWithoutResult(status -> {
            Work work = entities.find(Work.class, run.workId());
            entities.persist(WorkCharacter.create(work, "작가 확인 인물", null, null, null, null, null, null, null, null, null));
            entities.persist(WorldSetting.create(work, WorldSettingCategory.RACE, "작가 확인 종족", "수명", "천 년"));
        });
        JsonNode input = input(claim());
        assertThat(input.path("characters").elements().next().path("provenance").path("reviewSource").asText()).isEqualTo("HUMAN");
        assertThat(input.path("worldSettings").elements().next().path("provenance").path("reviewSource").asText()).isEqualTo("HUMAN");
    }

    @Test
    @DisplayName("같은 이름의 서로 다른 임시 인물은 자동 생성하거나 합치지 않고 연결된 설정도 보류한다")
    void sameNameDistinctAnchorsRemainPending() {
        run(2);
        WorkerAnalysisJobPayload first = claim();
        List<UUID> anchors = tx.execute(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            SettingCandidate left = discovery(job, "에르웬", null, true);
            SettingCandidate right = discovery(job, " 에르웬 ", null, true);
            addStat(job, left.getProvisionalSubjectKey(), null, 35, CharacterFactOperation.ADD);
            return List.of(left.getId(), right.getId());
        });
        complete(first);
        assertThat(tx.<Long>execute(status -> entities.createQuery("select count(c) from WorkCharacter c", Long.class).getSingleResult())).isZero();
        tx.executeWithoutResult(status -> assertThat(candidates.findAllById(anchors))
                .allSatisfy(candidate -> assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW)));
        JsonNode second = input(claim());
        assertThat(second.path("characters").size()).isZero();
        assertThat(second.path("references").size()).isEqualTo(3);
    }

    @Test
    @DisplayName("원문 파기는 다음 회차에 복사된 근거와 보류 정보 및 수동 새 실행 S0의 근거를 지우고 자동 새 실행은 표지만 남긴다")
    void purgesCopiedIdentityEvidenceAndPendingReferences() {
        Run run = run(2);
        WorkerAnalysisJobPayload first = claim();
        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            discovery(job, "에르웬", null, true);
            SettingCandidate pending = SettingCandidate.create(job.getWork(), job.getEpisode(), null, job,
                    SettingEntityType.CHARACTER, "미상", "stats.mental", "99", SettingValueType.NUMBER,
                    JSON.objectNode().put("value", 99), evidence("파기할 보류 근거"), BigDecimal.ONE, null);
            entities.persist(pending);
        });
        complete(first);
        WorkerAnalysisJobPayload second = claim();
        assertThat(input(second).toString()).contains("에르웬라고 말했다.", "파기할 보류 근거");
        List<UUID> freshRunRoots = tx.execute(status -> {
            AnalysisJob next = jobs.findById(second.analysisJobId()).orElseThrow();
            AnalysisJob fresh = AnalysisJob.create(next.getWork(), next.getBatch(), next.getEpisode(), AnalysisJobType.SETTING_EXTRACTION);
            fresh.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
            states.initializeRun(List.of(fresh));
            // #387 자동 누적은 생성 때 설정을 복사하지 않는다. 근거는 claim 때 automatic_input_state에만 들어간다.
            assertThat(fresh.getRunBaseState()).isEqualTo(JSON.objectNode().put("automaticInputCapturedAtClaim", true));
            AnalysisJob manual = AnalysisJob.create(next.getWork(), next.getBatch(), next.getEpisode(), AnalysisJobType.SETTING_EXTRACTION);
            manual.configureReviewMode(AnalysisReviewMode.MANUAL);
            states.initializeRun(List.of(manual));
            assertThat(manual.getRunBaseState().toString()).contains("에르웬라고 말했다.");
            return List.of(fresh.getId(), manual.getId());
        });
        tx.executeWithoutResult(status -> {
            entities.lock(entities.find(Work.class, run.workId()), jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            states.purgeSourceEvidenceForWorkForUpdate(run.workId(),
                    jobs.findById(first.analysisJobId()).orElseThrow().getEpisode().getId(), 1);
        });
        tx.executeWithoutResult(status -> {
            AnalysisJob secondJob = jobs.findById(second.analysisJobId()).orElseThrow();
            assertThat(secondJob.getJournalStatus()).isEqualTo(AnalysisJournalStatus.INVALIDATED);
            assertThat(secondJob.getAutomaticInputState().toString()).doesNotContain("에르웬라고 말했다.", "파기할 보류 근거");
            assertThat(secondJob.getAutomaticInputState().path("references").size()).isZero();
            JsonNode automaticBase = jobs.findById(freshRunRoots.get(0)).orElseThrow().getRunBaseState();
            assertThat(automaticBase.path("automaticInputCapturedAtClaim").asBoolean()).isTrue();
            assertThat(automaticBase.has("characters")).isFalse();
            assertThat(automaticBase.has("worldSettings")).isFalse();
            JsonNode manualBase = jobs.findById(freshRunRoots.get(1)).orElseThrow().getRunBaseState();
            assertThat(manualBase.path("sourceEvidencePurged").asBoolean()).isTrue();
            assertThat(manualBase.toString()).doesNotContain("에르웬라고 말했다.");
        });
    }

    @Test
    @DisplayName("앞 원문 파기 후에도 완료된 후행 후보는 작가 선택으로 확정하고 세계관의 최신값 보호를 유지한다")
    void reviewsCompletedSuccessorAfterEarlierSourcePurge() {
        Run run = run(3);
        var first = claim();
        complete(first);
        var second = claim();
        List<UUID> pending = tx.execute(status -> {
            AnalysisJob job = jobs.findById(second.analysisJobId()).orElseThrow();
            UUID character = addUnresolvedReference(job).getId();
            WorldSettingCandidate world = WorldSettingCandidate.create(job.getWork(), job.getEpisode(), job,
                    WorldSettingCategory.RACE, "엘프", "서식지", "남부", JSON.arrayNode(), BigDecimal.ONE, null);
            entities.persist(world);
            String key = "provisional-world:" + world.getId();
            world.resolveOrderedSubject(WorldSettingSubjectResolutionType.NEW, key, "엘프", JSON.arrayNode(), JSON.arrayNode().add(key));
            WorldSettingComparisonBatch comparison = WorldSettingComparisonBatch.createOrdered(job.getWork(), job.getEpisode(), job,
                    WorldSettingCategory.RACE, null, WorldSettingSubjectResolutionType.NEW, key, "엘프", JSON.arrayNode(), JSON.arrayNode().add(key), 1);
            entities.persist(comparison);
            world.startComparison(comparison, "C1");
            comparison.recordContext(JSON.objectNode().putObject("targets").putObject(key));
            world.failComparison(AnalysisFailureCode.LLM_RESPONSE_PARSE_ERROR, "직접 확인 필요");
            comparison.fail(AnalysisFailureCode.LLM_RESPONSE_PARSE_ERROR, "직접 확인 필요");
            return List.of(character, world.getId());
        });
        complete(second);
        var third = claim();
        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(third.analysisJobId()).orElseThrow();
            var discovered = discovery(job, "에르웬", null, true);
            addStat(job, discovered.getProvisionalSubjectKey(), null, 36, CharacterFactOperation.ADD);
            addWorld(job);
        });
        complete(third);
        Long owner = tx.execute(status -> entities.find(Work.class, run.workId()).getMember().getId());
        UUID character = tx.execute(status -> entities.createQuery("select c.id from WorkCharacter c where c.work.id = :work", UUID.class)
                .setParameter("work", run.workId()).getSingleResult());
        tx.executeWithoutResult(status -> {
            AnalysisJob changed = jobs.findById(first.analysisJobId()).orElseThrow();
            entities.lock(changed.getWork(), jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            states.invalidateRunsForEpisodeChangeForUpdate(run.workId(), changed.getEpisode().getId(), 1, "원문 교체");
            changed.getEpisode().updateContent(1, "새 원문", "test/replaced-1", "v2", "d".repeat(64), 20);
            states.purgeSourceEvidenceForWorkForUpdate(run.workId(), changed.getEpisode().getId(), 1);
            entities.createQuery("update CharacterFactComparisonBatch b set b.analysisContextSnapshotJson = null where b.work.id = :work")
                    .setParameter("work", run.workId()).executeUpdate();
            entities.createQuery("update WorldSettingComparisonBatch b set b.contextSnapshotJson = null where b.work.id = :work")
                    .setParameter("work", run.workId()).executeUpdate();
        });
        characterReview.updateSettingCandidateCharacterMatch(owner, run.workId(), pending.getFirst(),
                new org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateCharacterMatchRequest(
                        org.monitoring.catchholebackend.domain.character.type.SettingCandidateCharacterMatchResolutionType.MATCH_EXISTING, character, null));
        LocalDateTime beforeReview = tx.execute(status -> candidates.findById(pending.getFirst()).orElseThrow().getUpdatedAt());
        var reviewed = characterReview.updateSettingCandidate(owner, run.workId(), pending.getFirst(),
                new SettingCandidateUpdateRequest("stats.mental", "35", CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, beforeReview));
        LocalDateTime savedReviewedAt = tx.execute(status -> candidates.findById(pending.getFirst()).orElseThrow().getUpdatedAt());
        assertThat(reviewed.updatedAt().plusNanos(500).truncatedTo(java.time.temporal.ChronoUnit.MICROS))
                .isEqualTo(savedReviewedAt.plusNanos(500).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(characterReview.confirmSettingCandidate(owner, run.workId(), pending.getFirst(),
                new SettingCandidateConfirmRequest(CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, null, true, reviewed.updatedAt())).recomparisonRequired()).isFalse();
        UUID batch = tx.execute(status -> jobs.findById(second.analysisJobId()).orElseThrow().getBatch().getId());
        worldApplication.updateCandidateDecisions(owner, run.workId(), new WorldSettingCandidateDecisionUpdateRequest(batch,
                List.of(new WorldSettingCandidateDecisionUpdateItem(pending.get(1), WorldSettingOperation.ADD,
                        WorldSettingCategory.RACE, "엘프", null, "서식지", "남부", "직접 확인"))));
        assertThat(worldApplication.confirmCandidate(owner, run.workId(), pending.get(1),
                new WorldSettingCandidateConfirmRequest(WorldSettingOperation.ADD, WorldSettingCategory.RACE,
                        "엘프", null, "서식지", "남부", true, "직접 확인")).recomparisonRequired()).isFalse();
        tx.executeWithoutResult(status -> {
            assertThat(candidates.findById(pending.getFirst()).orElseThrow().getConfirmedApplicationMode())
                    .isEqualTo(CharacterFactConfirmApplicationMode.APPLY_PROPOSAL);
            assertThat(entities.find(WorldSettingCandidate.class, pending.get(1)).isHistoryOnly()).isTrue();
            assertThat(jobs.count()).isEqualTo(3);
            assertThat(jobs.findById(second.analysisJobId()).orElseThrow().isCompletedOrderedAnalysis()).isTrue();
            assertThat(jobs.findById(third.analysisJobId()).orElseThrow().isCompletedOrderedAnalysis()).isTrue();
        });
        nextBatch(run.workId(), 4);
        var current = input(claim());
        assertThat(currentFactValues(current)).contains("35").doesNotContain("36");

        assertThat(current.path("worldSettings").toString()).contains("북부").doesNotContain("남부");
    }

    @Test
    @DisplayName("개별 비교 실패는 후보로 남기고 같은 회차의 정상 캐릭터·세계관을 저장해 다음 회차를 연다")
    void failedComparisonsStayAsReferencesWhileValidSettingsApply() {
        run(2);
        WorkerAnalysisJobPayload first = claim();
        List<UUID> failedIds = tx.execute(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            SettingCandidate discovery = discovery(job, "에르웬", null, true);
            addStat(job, discovery.getProvisionalSubjectKey(), null, 35, CharacterFactOperation.ADD);
            addWorld(job);
            SettingCandidate failedCharacter = SettingCandidate.create(job.getWork(), job.getEpisode(), null, job,
                    SettingEntityType.CHARACTER, "에르웬", "stats.mental", "99", SettingValueType.NUMBER,
                    JSON.objectNode().put("value", 99), evidence("정신 수치는 99였다."), BigDecimal.ONE, null);
            ReflectionTestUtils.setField(failedCharacter, "provisionalSubjectKey", discovery.getProvisionalSubjectKey());
            entities.persist(failedCharacter);
            entities.flush();
            characterStates.prepareProvisionalCandidates(job);
            failPreparedCharacterComparison(job, failedCharacter, "개별 비교 해석 실패");
            WorldSettingCandidate failedWorld = WorldSettingCandidate.create(job.getWork(), job.getEpisode(), job,
                    WorldSettingCategory.RACE, "판단 실패 종족", "수명", "미확인 값", evidence("해당 종족의 수명을 알 수 없다."), BigDecimal.ONE, null);
            entities.persist(failedWorld);
            String key = "provisional-world:" + failedWorld.getId();
            failedWorld.resolveOrderedSubject(WorldSettingSubjectResolutionType.NEW, key, "판단 실패 종족", JSON.arrayNode(), JSON.arrayNode().add(key));
            WorldSettingComparisonBatch batch = WorldSettingComparisonBatch.createOrdered(job.getWork(), job.getEpisode(), job,
                    WorldSettingCategory.RACE, null, WorldSettingSubjectResolutionType.NEW, key, "판단 실패 종족", JSON.arrayNode(), JSON.arrayNode().add(key), 1);
            entities.persist(batch);
            failedWorld.startComparison(batch, "C1");
            batch.recordContext(JSON.objectNode().putObject("targets").putObject(key));
            failedWorld.failComparison(AnalysisFailureCode.LLM_RESPONSE_PARSE_ERROR, "개별 비교 해석 실패");
            batch.fail(AnalysisFailureCode.LLM_RESPONSE_PARSE_ERROR, "개별 비교 해석 실패");
            return List.of(failedCharacter.getId(), failedWorld.getId());
        });
        complete(first);
        WorkerAnalysisJobPayload second = claim();
        JsonNode input = input(second);
        assertThat(input.path("characters").size()).isEqualTo(1);
        assertThat(input.path("characters").elements().next().path("slots").path("STAT:stats.mental").path("factValue").asText()).isEqualTo("35");
        assertThat(input.path("worldSettings").size()).isEqualTo(1);
        assertThat(input.path("worldSettings").toString()).doesNotContain("판단 실패 종족");
        assertThat(input.path("references").path("pending-character:" + failedIds.get(0)).path("value").asText()).isEqualTo("99");
        assertThat(input.path("references").path("pending-world:" + failedIds.get(1)).path("value").asText()).isEqualTo("미확인 값");
        tx.executeWithoutResult(status -> {
            SettingCandidate character = candidates.findById(failedIds.get(0)).orElseThrow();
            WorldSettingCandidate world = entities.find(WorldSettingCandidate.class, failedIds.get(1));
            assertThat(character.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
            assertThat(character.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.FAILED);
            assertThat(world.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.PENDING_REVIEW);
            assertThat(world.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.FAILED);
            assertThat(jobs.findById(first.analysisJobId()).orElseThrow().getAutomaticAppliedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("한 세계관 batch의 정상 결정과 실패 진단을 함께 저장하고 다음 회차에는 정상값과 실패 참고만 전달한다")
    void mixedWorldCompletionKeepsFailureAndAdvancesNextEpisode() {
        run(2);
        var first = claim();
        List<UUID> ids = tx.execute(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            var good = WorldSettingCandidate.create(job.getWork(), job.getEpisode(), job, WorldSettingCategory.RACE,
                    "엘프", "서식지", "북부", evidence("엘프는 북부에 산다."), BigDecimal.ONE, null);
            var failed = WorldSettingCandidate.create(job.getWork(), job.getEpisode(), job, WorldSettingCategory.RACE,
                    "엘프", "수명", "미확인 수명", evidence("엘프의 수명을 알 수 없다."), BigDecimal.ONE, null);
            entities.persist(good);
            entities.persist(failed);
            entities.flush();
            ReflectionTestUtils.setField(good, "createdAt", LocalDateTime.now().minusSeconds(1));
            return List.of(good.getId(), failed.getId());
        });
        String key = "provisional-world:" + ids.getFirst();
        worldWorker.resolveWorldSettingSubjects(first.analysisJobId(), first.leaseToken(), new WorkerWorldSettingSubjectResolutionRequest(
                ids.stream().map(id -> new WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput(id, List.of(), List.of(key), false)).toList()));
        var batch = worldWorker.claimNextWorldSettingComparisonBatch(first.analysisJobId(), first.leaseToken()).orElseThrow();
        var context = worldWorker.getWorldSettingComparisonBatchContext(first.analysisJobId(), batch.comparisonBatchId(), first.leaseToken(),
                new WorkerWorldSettingComparisonBatchContextRequest(List.of(), List.of(key)));
        var initial = new org.monitoring.catchholebackend.domain.worldsetting.dto.WorldSettingComparisonDiagnostic(
                1, "RESPONSE_PARSE_ERROR", List.of(), List.of());
        var failedAttempt = new org.monitoring.catchholebackend.domain.worldsetting.dto.WorldSettingComparisonDiagnostic(
                2, "PROPOSED_PATH_MISMATCH", List.of("C2"), List.of());
        var request = new WorkerWorldSettingComparisonBatchCompleteRequest(
                List.of(new WorkerWorldSettingComparisonBatchCompleteRequest.ContextVersion(null, context.targets().getFirst().version(), key)),
                List.of(new WorkerWorldSettingComparisonBatchCompleteRequest.Decision("D1", List.of("C1"), "엘프", null,
                        null, null, List.of(), WorldSettingConsolidationStatus.SINGLE, WorldSettingSuggestedOperation.ADD, null,
                        null, "서식지", "북부", "확인된 서식지", Map.of(), key)), Map.of(), context.contextToken(),
                List.of(new WorkerWorldSettingComparisonBatchCompleteRequest.Failure(List.of("C2"),
                        AnalysisFailureCode.COMPARISON_VALIDATION_FAILED, "검증 실패", List.of(failedAttempt))), List.of(initial));
        var conflicting = new WorkerWorldSettingComparisonBatchCompleteRequest(request.contextVersions(),
                List.of(request.decisions().getFirst(), new WorkerWorldSettingComparisonBatchCompleteRequest.Decision("D2", List.of("C2"), "엘프", null,
                        null, null, List.of(), WorldSettingConsolidationStatus.SINGLE, WorldSettingSuggestedOperation.ADD, null,
                        null, "서식지", "덮어쓸 값", "겹친 경로", Map.of(), key)), Map.of(), context.contextToken());
        assertThatThrownBy(() -> worldWorker.completeWorldSettingComparisonBatch(first.analysisJobId(), batch.comparisonBatchId(), first.leaseToken(), conflicting))
                .isInstanceOf(AppException.class);
        tx.executeWithoutResult(status -> {
            assertThat(entities.find(WorldSettingCandidate.class, ids.getFirst()).getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.PROCESSING);
            assertThat(entities.find(WorldSettingCandidate.class, ids.getLast()).getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.PROCESSING);
            assertThat(entities.createQuery("select count(d) from WorldSettingComparisonDecision d", Long.class).getSingleResult()).isZero();
        });
        worldWorker.completeWorldSettingComparisonBatch(first.analysisJobId(), batch.comparisonBatchId(), first.leaseToken(), request);
        worldWorker.completeWorldSettingComparisonBatch(first.analysisJobId(), batch.comparisonBatchId(), first.leaseToken(), request);
        complete(first);

        tx.executeWithoutResult(status -> {
            var good = entities.find(WorldSettingCandidate.class, ids.getFirst());
            var failed = entities.find(WorldSettingCandidate.class, ids.getLast());
            assertThat(good.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED);
            assertThat(good.getComparisonDiagnostics().size()).isEqualTo(1);
            assertThat(failed.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.FAILED);
            assertThat(failed.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.PENDING_REVIEW);
            assertThat(failed.getComparisonDiagnostics().size()).isEqualTo(2);
            var response = new org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingMapper(org.mockito.Mockito.mock(org.monitoring.catchholebackend.domain.analysis.processor.GeneralEvidenceProjection.class), org.mockito.Mockito.mock(CandidateReviewSourceScope.class)).toCandidateResponse(failed);
            assertThat(response.comparisonDiagnostics()).hasSize(2);
            assertThat(response.comparisonDiagnostics().getLast().rule()).isEqualTo("PROPOSED_PATH_MISMATCH");
            assertThat(jobs.findById(first.analysisJobId()).orElseThrow().getJournalStatus()).isEqualTo(AnalysisJournalStatus.SEALED);
        });
        var second = input(claim());
        assertThat(second.path("worldSettings").size()).isEqualTo(1);
        assertThat(second.path("worldSettings").toString()).contains("북부").doesNotContain("미확인 수명");
        assertThat(second.path("references").path("pending-world:" + ids.getLast()).path("value").asText()).isEqualTo("미확인 수명");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("자동 반영으로 실제 버전이 바뀌어도 이전 AI 버전을 보낸 명시적 직접 확인은 단건과 그룹 모두 저장한다")
    void manuallyConfirmsFailedCharacterValueAfterAutomaticCompletion(boolean groupConfirm) {
        Run run = run(1);
        WorkerAnalysisJobPayload first = claim();
        UUID failedId = tx.execute(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            SettingCandidate discovery = discovery(job, "에르웬", null, true);
            addStat(job, discovery.getProvisionalSubjectKey(), null, 35, CharacterFactOperation.ADD);
            SettingCandidate failed = SettingCandidate.create(job.getWork(), job.getEpisode(), null, job,
                    SettingEntityType.CHARACTER, "에르웬", "stats.mental", "99", SettingValueType.NUMBER,
                    JSON.objectNode().put("value", 99), evidence("정신 수치는 99였다."), BigDecimal.ONE, null);
            ReflectionTestUtils.setField(failed, "provisionalSubjectKey", discovery.getProvisionalSubjectKey());
            entities.persist(failed);
            entities.flush();
            characterStates.prepareProvisionalCandidates(job);
            failPreparedCharacterComparison(job, failed, "직접 검토할 비교 실패");
            return failed.getId();
        });
        complete(first);
        Long ownerId = tx.execute(status -> entities.find(Work.class, run.workId()).getMember().getId());
        assertThat(tx.<Boolean>execute(status -> candidates.findById(failedId).orElseThrow().isManualReviewAvailable())).isTrue();
        LocalDateTime beforeReview = tx.execute(status -> candidates.findById(failedId).orElseThrow().getUpdatedAt());
        var reviewed = characterReview.updateSettingCandidate(ownerId, run.workId(), failedId,
                new SettingCandidateUpdateRequest("stats.mental", "99", CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, beforeReview));
        UUID batchId = tx.execute(status -> jobs.findById(first.analysisJobId()).orElseThrow().getBatch().getId());
        tx.executeWithoutResult(status -> {
            WorkCharacter character = entities.createQuery("select c from WorkCharacter c where c.work.id = :id", WorkCharacter.class)
                    .setParameter("id", run.workId()).getSingleResult();
            assertThat(character.getSnapshotVersion()).isPositive();
        });
        if (groupConfirm) {
            var result = characterReview.confirmSettingCandidateGroup(ownerId, run.workId(),
                    new SettingCandidateGroupConfirmRequest(batchId, List.of(new SettingCandidateGroupConfirmDecision(
                            failedId, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, 0L, true, reviewed.updatedAt()))));
            assertThat(result.recomparisonRequired()).isFalse();
        } else {
            var result = characterReview.confirmSettingCandidate(ownerId, run.workId(), failedId,
                    new SettingCandidateConfirmRequest(CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, 0L, true, reviewed.updatedAt()));
            assertThat(result.recomparisonRequired()).isFalse();
        }
        tx.executeWithoutResult(status -> {
            SettingCandidate candidate = candidates.findById(failedId).orElseThrow();
            assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
            assertThat(candidate.isUserModified()).isTrue();
            assertThat(candidate.isReviewedAutomatically()).isFalse();
            assertThat(candidate.getMatchedCharacterId()).isNotNull();
            List<CharacterFact> facts = entities.createQuery("select f from CharacterFact f where f.workCharacter.id = :id", CharacterFact.class)
                    .setParameter("id", candidate.getMatchedCharacterId()).getResultList();
            assertThat(facts).hasSize(2).anySatisfy(fact -> assertThat(fact.getFactValue()).isEqualTo("99"));
        });
    }

    @Test
    @DisplayName("자동 반영 후 남은 세계관 실패 후보의 수정안을 저장하고 확정하면 실제 세계관과 작가 검토자를 남긴다")
    void manuallyConfirmsFailedWorldDraftAfterAutomaticCompletion() {
        Run run = run(1);
        WorkerAnalysisJobPayload first = claim();
        UUID failedId = tx.execute(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            addWorld(job);
            WorldSettingCandidate failed = WorldSettingCandidate.create(job.getWork(), job.getEpisode(), job,
                    WorldSettingCategory.RACE, "설인", "서식지", "미확인", JSON.arrayNode(), BigDecimal.ONE, null);
            entities.persist(failed);
            String key = "provisional-world:" + failed.getId();
            failed.resolveOrderedSubject(WorldSettingSubjectResolutionType.NEW, key, "설인", JSON.arrayNode(), JSON.arrayNode().add(key));
            WorldSettingComparisonBatch batch = WorldSettingComparisonBatch.createOrdered(job.getWork(), job.getEpisode(), job,
                    WorldSettingCategory.RACE, null, WorldSettingSubjectResolutionType.NEW, key, "설인", JSON.arrayNode(), JSON.arrayNode().add(key), 1);
            entities.persist(batch);
            failed.startComparison(batch, "C1");
            batch.recordContext(JSON.objectNode().putObject("targets").putObject(key));
            failed.failComparison(AnalysisFailureCode.LLM_RESPONSE_PARSE_ERROR, "직접 검토할 비교 실패");
            batch.fail(AnalysisFailureCode.LLM_RESPONSE_PARSE_ERROR, "직접 검토할 비교 실패");
            return failed.getId();
        });
        complete(first);
        Long ownerId = tx.execute(status -> entities.find(Work.class, run.workId()).getMember().getId());
        UUID batchId = tx.execute(status -> jobs.findById(first.analysisJobId()).orElseThrow().getBatch().getId());
        assertThat(tx.<Boolean>execute(status -> entities.find(WorldSettingCandidate.class, failedId).isManualReviewAvailable())).isTrue();
        worldApplication.updateCandidateDecisions(ownerId, run.workId(), new WorldSettingCandidateDecisionUpdateRequest(batchId,
                List.of(new WorldSettingCandidateDecisionUpdateItem(failedId, WorldSettingOperation.ADD, WorldSettingCategory.RACE,
                        "설인", null, "서식지", "눈 덮인 산맥", "작가 확인"))));
        var result = worldApplication.confirmCandidate(ownerId, run.workId(), failedId,
                new WorldSettingCandidateConfirmRequest(WorldSettingOperation.ADD, WorldSettingCategory.RACE,
                        "설인", null, "서식지", "눈 덮인 산맥", true, "작가 확인"));
        assertThat(result.recomparisonRequired()).isFalse();
        tx.executeWithoutResult(status -> {
            WorldSettingCandidate candidate = entities.find(WorldSettingCandidate.class, failedId);
            assertThat(candidate.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED);
            assertThat(candidate.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.FAILED);
            assertThat(candidate.isReviewedAutomatically()).isFalse();
            assertThat(candidate.getReviewedBy().getId()).isEqualTo(ownerId);
            assertThat(candidate.getTargetWorldSetting().getPropertiesJson().path("서식지").asText()).isEqualTo("눈 덮인 산맥");
            assertThat(entities.createQuery("select count(w) from WorldSetting w", Long.class).getSingleResult()).isEqualTo(2L);
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = AnalysisFailureCode.class, names = {"LLM_OUTPUT_TRUNCATED", "LLM_NETWORK_ERROR", "LLM_PROVIDER_ERROR",
            "LLM_RESPONSE_PARSE_ERROR", "COMPARISON_VALIDATION_FAILED"})
    @DisplayName("세계관 4개 묶음 검증 실패를 API 서비스로 기록한 뒤 다음 묶음과 회차가 계속되고 실패값은 참고에만 남는다")
    void continuesWorldBatchClaimsAfterRecoverableFailureAndAppliesOnlySuccessfulSettings(AnalysisFailureCode code) {
        Run run = run(2);
        WorkerAnalysisJobPayload first = claim();
        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            SettingCandidate discovered = discovery(job, "에르웬", null, true);
            addStat(job, discovered.getProvisionalSubjectKey(), null, 35, CharacterFactOperation.ADD);
        });
        List<UUID> worldIds = prepareWorldBatches(first);
        var failedBatch = worldWorker.claimNextWorldSettingComparisonBatch(first.analysisJobId(), first.leaseToken()).orElseThrow();
        assertThat(failedBatch.candidates()).hasSize(4);
        worldWorker.getWorldSettingComparisonBatchContext(first.analysisJobId(), failedBatch.comparisonBatchId(), first.leaseToken(),
                new WorkerWorldSettingComparisonBatchContextRequest(failedBatch.resolvedTargetWorldSettingIds(), failedBatch.resolvedProvisionalSubjectKeys()));
        var failure = new WorkerWorldSettingComparisonFailRequest(code, "묶음 LLM 비교 실패 재현", null, null,
                List.of(new org.monitoring.catchholebackend.domain.worldsetting.dto.WorldSettingComparisonDiagnostic(
                        1, "RESPONSE_PARSE_ERROR", List.of(), List.of()),
                        new org.monitoring.catchholebackend.domain.worldsetting.dto.WorldSettingComparisonDiagnostic(
                                2, "PROPOSED_PATH_MISMATCH", List.of("C1"), List.of())));
        worldWorker.failWorldSettingComparisonBatch(first.analysisJobId(), failedBatch.comparisonBatchId(), first.leaseToken(), failure);
        worldWorker.failWorldSettingComparisonBatch(first.analysisJobId(), failedBatch.comparisonBatchId(), first.leaseToken(), failure);
        tx.executeWithoutResult(status -> worldIds.subList(0, 4).forEach(id -> {
            var failed = entities.find(WorldSettingCandidate.class, id);
            assertThat(failed.getComparisonDiagnostics().size()).isEqualTo("C1".equals(failed.getComparisonCandidateRef()) ? 2 : 1);
        }));

        var nextBatch = worldWorker.claimNextWorldSettingComparisonBatch(first.analysisJobId(), first.leaseToken()).orElseThrow();
        assertThat(nextBatch.candidates()).singleElement().satisfies(candidate -> assertThat(candidate.candidateId()).isEqualTo(worldIds.getLast()));
        completeWorldBatch(first, nextBatch);
        assertThat(worldWorker.claimNextWorldSettingComparisonBatch(first.analysisJobId(), first.leaseToken())).isEmpty();
        complete(first);
        assertThatThrownBy(() -> complete(first)).isInstanceOf(RuntimeException.class);

        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            assertThat(job.getAutomaticAppliedAt()).isNotNull();
            assertThat(job.getJournalStatus()).isEqualTo(AnalysisJournalStatus.SEALED);
            var failedReferences = java.util.stream.StreamSupport.stream(job.getStateJournal().path("changes").spliterator(), false)
                    .filter(change -> change.path("eventId").asText().startsWith("world-comparison-failed:")).toList();
            assertThat(failedReferences).hasSize(4).allSatisfy(change -> {
                assertThat(change.path("path").get(0).asText()).isEqualTo("references");
                assertThat(change.path("value").path("confirmationStatus").asText()).isEqualTo("UNCONFIRMED");
                assertThat(change.path("value").path("evidenceSpans").toString()).contains("미궁 원문 근거");
            });
            for (UUID id : worldIds.subList(0, 4)) {
                WorldSettingCandidate candidate = entities.find(WorldSettingCandidate.class, id);
                assertThat(candidate.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.FAILED);
                assertThat(candidate.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.PENDING_REVIEW);
                assertThat(candidate.isManualReviewAvailable()).isTrue();
            }
            assertThat(entities.createQuery("select count(w) from WorldSetting w", Long.class).getSingleResult()).isEqualTo(1L);
            assertThat(entities.createQuery("select count(f) from CharacterFact f", Long.class).getSingleResult()).isEqualTo(1L);
            assertThat(entities.createQuery("select count(d) from WorldSettingComparisonDecision d", Long.class).getSingleResult()).isEqualTo(1L);
        });
        WorkerAnalysisJobPayload second = claim();
        assertThat(second.analysisJobId()).isEqualTo(run.jobs().getLast());
        JsonNode nextInput = input(second);
        assertThat(nextInput.path("worldSettings").size()).isEqualTo(1);
        assertThat(nextInput.path("worldSettings").toString()).contains("엘프", "북부").doesNotContain("미궁", "미확인 값");
        assertThat(nextInput.path("characters").size()).isEqualTo(1);
        assertThat(nextInput.path("characters").elements().next().path("slots").path("STAT:stats.mental").path("factValue").asText())
                .isEqualTo("35");
        for (UUID id : worldIds.subList(0, 4)) {
            JsonNode reference = nextInput.path("references").path("pending-world:" + id);
            assertThat(reference.path("subjectName").asText()).isEqualTo("미궁");
            assertThat(reference.path("confirmationStatus").asText()).isEqualTo("UNCONFIRMED");
            assertThat(reference.path("value").asText()).startsWith("미확인 값");
            assertThat(reference.path("evidenceSpans").toString()).contains("미궁 원문 근거");
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = AnalysisFailureCode.class, names = {"LLM_OUTPUT_TRUNCATED", "LLM_NETWORK_ERROR", "LLM_PROVIDER_ERROR",
            "LLM_RESPONSE_PARSE_ERROR", "COMPARISON_VALIDATION_FAILED"})
    @DisplayName("캐릭터 비교 묶음 실패 뒤 다른 인물 묶음을 계속 저장하고 실패 스탯은 다음 회차 실제값에 포함하지 않는다")
    void continuesCharacterBatchClaimsAfterRecoverableFailure(AnalysisFailureCode code) {
        Run run = run(2);
        WorkerAnalysisJobPayload first = claim();
        List<UUID> ids = prepareCharacterBatches(first);
        var failed = characterWorker.claimNextCharacterFactComparisonBatch(first.analysisJobId(), first.leaseToken()).orElseThrow();
        characterWorker.getCharacterFactComparisonBatchContext(first.analysisJobId(), failed.comparisonBatchId(), first.leaseToken());
        var failure = new WorkerCharacterFactComparisonFailRequest(code, "묶음 LLM 비교 실패 재현");
        characterWorker.failCharacterFactComparisonBatch(first.analysisJobId(), failed.comparisonBatchId(), first.leaseToken(), failure);
        characterWorker.failCharacterFactComparisonBatch(first.analysisJobId(), failed.comparisonBatchId(), first.leaseToken(), failure);
        var next = characterWorker.claimNextCharacterFactComparisonBatch(first.analysisJobId(), first.leaseToken()).orElseThrow();
        completeCharacterBatch(first, next.comparisonBatchId());
        assertThat(characterWorker.claimNextCharacterFactComparisonBatch(first.analysisJobId(), first.leaseToken())).isEmpty();
        complete(first);
        WorkerAnalysisJobPayload second = claim();
        assertThat(second.analysisJobId()).isEqualTo(run.jobs().getLast());
        JsonNode nextInput = input(second);
        List<JsonNode> identities = java.util.stream.StreamSupport.stream(nextInput.path("characters").spliterator(), false).toList();
        assertThat(identities).hasSize(2).anySatisfy(character -> {
            assertThat(character.path("name").asText()).isEqualTo("에르웬");
            assertThat(character.path("slots").path("STAT:stats.mental").path("factValue").asText()).isEqualTo("35");
        }).anySatisfy(character -> {
            assertThat(character.path("name").asText()).isEqualTo("비요른");
            assertThat(character.path("slots").has("STAT:stats.mental")).isFalse();
        });
        assertThat(nextInput.path("references").path("pending-character:" + ids.getFirst()).path("value").asText()).isEqualTo("99");
        tx.executeWithoutResult(status -> {
            assertThat(candidates.findById(ids.getFirst()).orElseThrow().getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.FAILED);
            assertThat(candidates.findById(ids.getFirst()).orElseThrow().getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
            assertThat(candidates.findById(ids.getLast()).orElseThrow().getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
            assertThat(entities.createQuery("select count(f) from CharacterFact f", Long.class).getSingleResult()).isEqualTo(1L);
        });
    }

    @ParameterizedTest(name = "{0} / {1} / {2} / context={3}")
    @MethodSource("blockingWorldFailures")
    @DisplayName("수동 검토 또는 실행·저장 경계 오류는 세계관 다음 묶음과 자동 저장 및 다음 회차를 계속 차단한다")
    void worldExecutionFailuresAndManualReviewStillBlock(AnalysisReviewMode mode, AnalysisFailureCode code, String sourceError, boolean contextReady) {
        run(2, mode);
        WorkerAnalysisJobPayload first = claim();
        prepareWorldBatches(first);
        var batch = worldWorker.claimNextWorldSettingComparisonBatch(first.analysisJobId(), first.leaseToken()).orElseThrow();
        var healthy = worldWorker.claimNextWorldSettingComparisonBatch(first.analysisJobId(), first.leaseToken()).orElseThrow();
        if (contextReady) worldWorker.getWorldSettingComparisonBatchContext(first.analysisJobId(), batch.comparisonBatchId(), first.leaseToken(),
                new WorkerWorldSettingComparisonBatchContextRequest(batch.resolvedTargetWorldSettingIds(), batch.resolvedProvisionalSubjectKeys()));
        worldWorker.failWorldSettingComparisonBatch(first.analysisJobId(), batch.comparisonBatchId(), first.leaseToken(),
                new WorkerWorldSettingComparisonFailRequest(code, "중단해야 하는 실패", sourceError, null));
        assertThatThrownBy(() -> worldWorker.claimNextWorldSettingComparisonBatch(first.analysisJobId(), first.leaseToken()))
                .isInstanceOf(OrderedWorldSettingComparisonClaimException.class);
        completeWorldBatch(first, healthy);
        complete(first);
        assertThat(worker.claimAnalysisJob(request())).isEmpty();
        tx.executeWithoutResult(status -> {
            assertIncompleteAndUnapplied(first);
            assertThat(entities.createQuery("select count(w) from WorldSetting w", Long.class).getSingleResult()).isZero();
        });
    }

    private void assertIncompleteAndUnapplied(WorkerAnalysisJobPayload payload) {
        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(payload.analysisJobId()).orElseThrow();
            // 기존 완료 API는 결과 수신과 누적 실행 봉인을 구별한다. 성공 응답으로 다음 회차가 열리면 안 된다.
            assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
            assertThat(job.getJournalStatus()).isEqualTo(AnalysisJournalStatus.INCOMPLETE);
            assertThat(job.getAutomaticAppliedAt()).isNull();
        });
    }

    private static Stream<Arguments> blockingWorldFailures() {
        return Stream.of(
                Arguments.of(AnalysisReviewMode.MANUAL, AnalysisFailureCode.LLM_RESPONSE_PARSE_ERROR, null, true),
                Arguments.of(AnalysisReviewMode.AUTOMATIC, AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED, null, true),
                Arguments.of(AnalysisReviewMode.AUTOMATIC, AnalysisFailureCode.WORKER_LEASE_EXPIRED, null, true),
                Arguments.of(AnalysisReviewMode.AUTOMATIC, AnalysisFailureCode.UNEXPECTED_ERROR, null, true),
                Arguments.of(AnalysisReviewMode.AUTOMATIC, AnalysisFailureCode.COMPARISON_VALIDATION_FAILED,
                        "WORLD_SETTING_CANDIDATE_COMPARISON_CONTEXT_STALE", true),
                Arguments.of(AnalysisReviewMode.AUTOMATIC, AnalysisFailureCode.COMPARISON_VALIDATION_FAILED, null, false));
    }

    @ParameterizedTest(name = "{0} / {1} / context={2}")
    @MethodSource("blockingCharacterFailures")
    @DisplayName("수동 검토 또는 실행 자체 오류는 캐릭터 다음 비교 묶음과 다음 회차를 차단한다")
    void characterExecutionFailuresAndManualReviewStillBlock(AnalysisReviewMode mode, AnalysisFailureCode code, boolean contextReady) {
        run(2, mode);
        WorkerAnalysisJobPayload first = claim();
        prepareCharacterBatches(first);
        var batch = characterWorker.claimNextCharacterFactComparisonBatch(first.analysisJobId(), first.leaseToken()).orElseThrow();
        var healthy = characterWorker.claimNextCharacterFactComparisonBatch(first.analysisJobId(), first.leaseToken()).orElseThrow();
        if (contextReady) characterWorker.getCharacterFactComparisonBatchContext(first.analysisJobId(), batch.comparisonBatchId(), first.leaseToken());
        characterWorker.failCharacterFactComparisonBatch(first.analysisJobId(), batch.comparisonBatchId(), first.leaseToken(),
                new WorkerCharacterFactComparisonFailRequest(code, "중단해야 하는 실패"));
        assertThatThrownBy(() -> characterWorker.claimNextCharacterFactComparisonBatch(first.analysisJobId(), first.leaseToken()))
                .isInstanceOf(OrderedCharacterComparisonClaimException.class);
        completeCharacterBatch(first, healthy.comparisonBatchId());
        complete(first);
        assertIncompleteAndUnapplied(first);
        assertThat(worker.claimAnalysisJob(request())).isEmpty();
    }

    private static Stream<Arguments> blockingCharacterFailures() {
        return Stream.of(
                Arguments.of(AnalysisReviewMode.MANUAL, AnalysisFailureCode.LLM_RESPONSE_PARSE_ERROR, true),
                Arguments.of(AnalysisReviewMode.AUTOMATIC, AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED, true),
                Arguments.of(AnalysisReviewMode.AUTOMATIC, AnalysisFailureCode.WORKER_LEASE_EXPIRED, true),
                Arguments.of(AnalysisReviewMode.AUTOMATIC, AnalysisFailureCode.UNEXPECTED_ERROR, true),
                Arguments.of(AnalysisReviewMode.AUTOMATIC, AnalysisFailureCode.LLM_RESPONSE_PARSE_ERROR, false));
    }

    // ---- #387 format 1(claim 사본)과 format 2(live 읽기)의 같은 실행 비교 ----

    @Test
    @DisplayName("#387 같은 3회차 실행을 claim 입력 사본(format 1)과 live 읽기(format 2)로 돌리면 claim 응답·비교 문맥·journal 변경·최종 DB가 같다")
    void liveInputMatchesFrozenInputAcrossRun() {
        // 설정값과 무관하게 두 형식을 모두 돌린다. 기본 실행(format 2)에서 format 1 claim·비교·봉인·완료를 확인하는 곳이다.
        Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(states);
        Object configured = ReflectionTestUtils.getField(target, "liveSettingsEnabled");
        try {
            ReflectionTestUtils.setField(target, "liveSettingsEnabled", false);
            List<String> frozen = renamed(equivalenceRun("frozen@example.invalid", "01000000001", false));
            ReflectionTestUtils.setField(target, "liveSettingsEnabled", true);
            List<String> live = renamed(equivalenceRun("live@example.invalid", "01000000002", true));
            assertThat(frozen).as("claim 3·Worker 응답 13·journal 2·최종 DB 7").hasSize(25);
            assertThat(live).hasSameSizeAs(frozen);
            for (int index = 0; index < frozen.size(); index++) {
                assertThat(live.get(index)).as("record %d: %s", index, firstDifference(frozen.get(index), live.get(index))).isEqualTo(frozen.get(index));
            }
        } finally {
            ReflectionTestUtils.setField(target, "liveSettingsEnabled", configured);
        }
    }

    @Test
    @DisplayName("#387 플래그를 켜기 전에 claim한 회차(format 1)는 켠 뒤의 lease 만료 재claim·실패 재시도에도 claim 사본으로 비교·봉인·자동 반영하고, 같은 실행의 다음 회차는 format 2로 claim하며 플래그를 다시 꺼도 format 2로 끝난다")
    void frozenInputJobClaimedBeforeFlagOnResumesAfterFlagOn() {
        Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(states);
        Object configured = ReflectionTestUtils.getField(target, "liveSettingsEnabled");
        try {
            // 배포 전 서버(플래그 꺼짐)가 1화를 claim해 claim 사본(format 1)을 저장했다.
            ReflectionTestUtils.setField(target, "liveSettingsEnabled", false);
            Run run = run(2);
            WorkerAnalysisJobPayload first = claim();
            assertStoredFormat(first, false);
            JsonNode frozen = storedInput(first);
            assertThat(frozen.has("characters")).isTrue();
            prepareCharacterBatches(first);

            // 배포: 플래그가 켜진 서버로 바뀌고 이전 Worker의 lease가 만료됐다. 같은 Job을 다시 claim해도 사본을 다시 만들지 않는다.
            ReflectionTestUtils.setField(target, "liveSettingsEnabled", true);
            WorkerAnalysisJobPayload reclaimed = claimAfterLeaseExpired(first.analysisJobId());
            assertThat(reclaimed.analysisJobId()).isEqualTo(first.analysisJobId());
            assertFrozenInputKept(reclaimed, first, frozen);
            var firstBatch = characterWorker.claimNextCharacterFactComparisonBatch(reclaimed.analysisJobId(), reclaimed.leaseToken()).orElseThrow();
            completeCharacterBatch(reclaimed, firstBatch.comparisonBatchId());

            // 실패 뒤 작가의 재시도도 같은 사본과 hash로 재개해 남은 비교를 마치고 봉인·자동 반영한다.
            worker.failAnalysisJob(reclaimed.analysisJobId(), reclaimed.leaseToken(),
                    new org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobFailRequest(
                            AnalysisFailureCode.UNEXPECTED_ERROR, "저장 연결 일시 실패"));
            Long owner = tx.execute(status -> entities.find(Work.class, run.workId()).getMember().getId());
            publicJobs.retryFailedAnalysisJob(owner, run.workId(), first.analysisJobId());
            WorkerAnalysisJobPayload retried = claim();
            assertThat(retried.analysisJobId()).isEqualTo(first.analysisJobId());
            assertFrozenInputKept(retried, first, frozen);
            var secondBatch = characterWorker.claimNextCharacterFactComparisonBatch(retried.analysisJobId(), retried.leaseToken()).orElseThrow();
            assertThat(secondBatch.comparisonBatchId()).isNotEqualTo(firstBatch.comparisonBatchId());
            completeCharacterBatch(retried, secondBatch.comparisonBatchId());
            assertThat(characterWorker.claimNextCharacterFactComparisonBatch(retried.analysisJobId(), retried.leaseToken())).isEmpty();
            complete(retried);
            assertAppliedAndSealed(first.analysisJobId());
            assertThat(storedInput(first)).isEqualTo(frozen);

            // 같은 실행의 2화는 켠 서버가 처음 claim하므로 format 2이고, 1화가 반영한 인물을 지금 DB에서 읽는다.
            WorkerAnalysisJobPayload second = claim();
            assertThat(second.analysisJobId()).isEqualTo(run.jobs().getLast());
            assertStoredFormat(second, true);
            assertThat(second.knownCharacters()).extracting(character -> character.name()).containsExactlyInAnyOrder("비요른", "에르웬");
            UUID erwen = tx.execute(status -> entities.createQuery("select c.id from WorkCharacter c where c.work.id = :w and c.name = '에르웬'", UUID.class)
                    .setParameter("w", run.workId()).getSingleResult());
            assertThat(tx.<String>execute(status -> states.getInputTarget(jobs.findById(second.analysisJobId()).orElseThrow(), "characters", "character:" + erwen)
                    .path("slots").path("STAT:stats.mental").path("factValue").asText())).isEqualTo("35");

            // 운영자가 플래그를 다시 꺼도 이미 format 2로 claim한 Job은 format 2로 재claim하고 끝난다.
            ReflectionTestUtils.setField(target, "liveSettingsEnabled", false);
            WorkerAnalysisJobPayload secondReclaimed = claimAfterLeaseExpired(second.analysisJobId());
            assertThat(secondReclaimed.analysisJobId()).isEqualTo(second.analysisJobId());
            assertStoredFormat(secondReclaimed, true);
            assertThat(secondReclaimed.analysisContext().inputStateHash()).isEqualTo(second.analysisContext().inputStateHash());
            complete(secondReclaimed);
            assertAppliedAndSealed(second.analysisJobId());
        } finally {
            ReflectionTestUtils.setField(target, "liveSettingsEnabled", configured);
        }
    }

    /**
     * lease를 만료시킨 뒤 Worker처럼 다시 claim한다. 만료 회수는 잠긴 Job 행을 건너뛰는데(SKIP LOCKED), 직전 커밋의 후보 상태 변경이
     * 부른 비동기 지표 관측(AnalysisMetricsReconciler)이 같은 행을 잠깐 잠글 수 있어 한 번의 claim은 빈 결과일 수 있다.
     */
    private WorkerAnalysisJobPayload claimAfterLeaseExpired(UUID jobId) {
        jdbc.update("update analysis_jobs set lease_expires_at=? where id=?", LocalDateTime.now().minusMinutes(1), jobId);
        for (int attempt = 0; attempt < 100; attempt++) {
            var claimed = worker.claimAnalysisJob(request());
            if (claimed.isPresent()) return claimed.get();
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
        throw new AssertionError("만료 lease의 Job을 다시 claim하지 못했습니다: " + jobId);
    }

    private void assertFrozenInputKept(WorkerAnalysisJobPayload payload, WorkerAnalysisJobPayload original, JsonNode frozen) {
        assertStoredFormat(payload, false);
        assertThat(storedInput(payload)).isEqualTo(frozen);
        assertThat(payload.analysisContext().inputStateHash()).isEqualTo(original.analysisContext().inputStateHash());
    }

    private void assertAppliedAndSealed(UUID jobId) {
        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(jobId).orElseThrow();
            assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
            assertThat(job.getJournalStatus()).isEqualTo(AnalysisJournalStatus.SEALED);
            assertThat(job.getAutomaticAppliedAt()).isNotNull();
        });
    }

    /** 1화: 새 인물 둘·새 세계관, 2화: 기존 인물 스탯 변경·기존 세계관 새 속성, 3화 claim. Worker 내부 API로 비교 문맥을 받는다. */
    private List<String> equivalenceRun(String email, String phone, boolean expectLive) {
        List<String> records = new ArrayList<>();
        Run run = runFor(3, email, phone);
        WorkerAnalysisJobPayload first = claimWithSemantic();
        records.add("claim1 " + wire(first));
        assertStoredFormat(first, expectLive);
        prepareCharacterBatches(first);
        for (var batch = characterWorker.claimNextCharacterFactComparisonBatch(first.analysisJobId(), first.leaseToken());
             batch.isPresent(); batch = characterWorker.claimNextCharacterFactComparisonBatch(first.analysisJobId(), first.leaseToken())) {
            records.add("characterBatch1 " + wire(batch.get()));
            var context = characterWorker.getCharacterFactComparisonBatchContext(first.analysisJobId(), batch.get().comparisonBatchId(), first.leaseToken());
            records.add("characterContext1 " + wire(context));
            characterWorker.completeCharacterFactComparisonBatch(first.analysisJobId(), batch.get().comparisonBatchId(), first.leaseToken(),
                    new WorkerCharacterFactComparisonBatchCompleteRequest(context.contextToken(),
                            List.of(new WorkerCharacterFactComparisonBatchCompleteRequest.Decision("C1", CharacterFactOperation.ADD,
                                    "stats.mental", null, List.of(), List.of(), "35", Map.of("value", 35),
                                    CharacterFactTemporalScope.PRESENT, "원문 수치", Map.of())), List.of(), Map.of()));
        }
        UUID firstWorld = publishWorld(first, "엘프", "서식지", "북부", "엘프는 북부에 산다.");
        records.add("pendingSubjects1 " + wire(worldWorker.getPendingWorldSettingSubjectResolutions(first.analysisJobId(), first.leaseToken())));
        worldWorker.resolveWorldSettingSubjects(first.analysisJobId(), first.leaseToken(), new WorkerWorldSettingSubjectResolutionRequest(
                List.of(new WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput(firstWorld, List.of(), List.of("provisional-world:" + firstWorld), false))));
        completeOnlyWorldBatch(first, records, "1", null, "서식지", "북부");
        complete(first);

        WorkerAnalysisJobPayload second = claimWithSemantic();
        records.add("claim2 " + wire(second));
        UUID erwen = tx.execute(status -> entities.createQuery("select c.id from WorkCharacter c where c.work.id = :w and c.name = '에르웬'", UUID.class)
                .setParameter("w", run.workId()).getSingleResult());
        UUID elves = tx.execute(status -> entities.createQuery("select w.id from WorldSetting w where w.work.id = :w", UUID.class)
                .setParameter("w", run.workId()).getSingleResult());
        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(second.analysisJobId()).orElseThrow();
            SettingCandidate candidate = SettingCandidate.create(job.getWork(), job.getEpisode(), null, job, SettingEntityType.CHARACTER,
                    "에르웬", "에르웬", erwen, SettingCandidateMatchStatus.MATCHED, "stats.mental", "36", SettingValueType.NUMBER,
                    JSON.objectNode().put("value", 36), evidence("stats.mental 수치는 36였다."), BigDecimal.ONE, null);
            entities.persist(candidate);
            job.updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_CANDIDATES_SAVED);
        });
        var batch = characterWorker.claimNextCharacterFactComparisonBatch(second.analysisJobId(), second.leaseToken()).orElseThrow();
        records.add("characterBatch2 " + wire(batch));
        var context = characterWorker.getCharacterFactComparisonBatchContext(second.analysisJobId(), batch.comparisonBatchId(), second.leaseToken());
        records.add("characterContext2 " + wire(context));
        String statRef = context.snapshotEntries().stream().filter(entry -> "stats.mental".equals(entry.factKey())).findFirst().orElseThrow().snapshotRef();
        characterWorker.completeCharacterFactComparisonBatch(second.analysisJobId(), batch.comparisonBatchId(), second.leaseToken(),
                new WorkerCharacterFactComparisonBatchCompleteRequest(context.contextToken(),
                        List.of(new WorkerCharacterFactComparisonBatchCompleteRequest.Decision("C1", CharacterFactOperation.UPDATE,
                                "stats.mental", statRef, List.of(), List.of(), "36", Map.of("value", 36),
                                CharacterFactTemporalScope.PRESENT, "원문 수치 변경", Map.of())), List.of(), Map.of()));
        assertThat(characterWorker.claimNextCharacterFactComparisonBatch(second.analysisJobId(), second.leaseToken())).isEmpty();
        UUID secondWorld = publishWorld(second, "엘프", "수명", "천 년", "엘프의 수명을 알 수 없다.");
        records.add("pendingSubjects2 " + wire(worldWorker.getPendingWorldSettingSubjectResolutions(second.analysisJobId(), second.leaseToken())));
        records.add("subjectPage2 " + wire(worldWorker.getWorldSettingSubjects(second.analysisJobId(), second.leaseToken(), WorldSettingCategory.RACE, 0, 50)));
        worldWorker.resolveWorldSettingSubjects(second.analysisJobId(), second.leaseToken(), new WorkerWorldSettingSubjectResolutionRequest(
                List.of(new WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput(secondWorld, List.of(elves), List.of(), false))));
        completeOnlyWorldBatch(second, records, "2", elves, "수명", "천 년");
        complete(second);

        WorkerAnalysisJobPayload third = claimWithSemantic();
        records.add("claim3 " + wire(third));
        for (UUID job : run.jobs().subList(0, 2)) {
            records.add("journal " + tx.execute(status -> jobs.findById(job).orElseThrow().getStateJournal().path("changes").toString()));
        }
        records.add("characters " + rows("select name, role_label, current_age, current_level, snapshot_version, current_narrative_time, profile_json, stats_json, skills_json, items_json, statuses_json, manually_edited_slots, status from characters where work_id=? order by name", run.workId()));
        records.add("facts " + rows("select c.name, f.fact_type, f.fact_key, f.fact_value, f.value_json, f.effective_from_episode_no from character_facts f join characters c on c.id=f.character_id where c.work_id=? order by c.name, f.fact_key, f.fact_value", run.workId()));
        records.add("sources " + rows("select c.name, s.fact_type, s.fact_key, s.source_order, f.fact_value from character_snapshot_sources s join characters c on c.id=s.character_id join character_facts f on f.id=s.source_fact_id where c.work_id=? order by c.name, s.fact_key, s.source_order", run.workId()));
        records.add("worlds " + rows("select category, subject_name, version, properties_json, manually_edited_paths from world_settings where work_id=? order by subject_name", run.workId()));
        records.add("characterCandidates " + rows("select entity_name, attribute_name, attribute_value, review_status, comparison_status, suggested_operation, proposed_fact_value, automatic_review_hold_reason, reviewed_automatically from setting_candidates where work_id=? order by entity_name, attribute_name, attribute_value, candidate_kind", run.workId()));
        records.add("worldCandidates " + rows("select subject_name, setting_name, extracted_value, review_status, comparison_status, suggested_operation, automatic_review_hold_reason, reviewed_automatically from world_setting_candidates where work_id=? order by subject_name, setting_name", run.workId()));
        records.add("jobs " + rows("select a.status, a.journal_status, a.checkpoint_stage, a.automatic_applied_at is not null as applied, e.episode_no from analysis_jobs a join episodes e on e.id=a.episode_id where a.work_id=? order by e.episode_no", run.workId()));
        return records;
    }

    /**
     * semantic 현재 문맥은 대상을 내부 ref(UUID 문자열) 순으로 싣는다. 두 실행의 UUID는 무작위라 같은 대상의 상대 순서가 실행마다 다를 수 있으므로
     * 이름을 바꾼 뒤 그 목록만 내용 순으로 맞춘다. 다른 목록의 순서는 그대로 비교한다.
     */
    private static String sortSemanticOwners(String record) {
        int split = record.indexOf(' ');
        if (split < 0 || !record.startsWith("claim")) return record;
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            JsonNode node = mapper.readTree(record.substring(split + 1));
            JsonNode semantic = node.path("semanticCurrentContext");
            for (String field : List.of("characters", "worldSubjects")) {
                if (!(semantic.path(field) instanceof com.fasterxml.jackson.databind.node.ArrayNode owners)) continue;
                List<JsonNode> sorted = new ArrayList<>();
                owners.forEach(sorted::add);
                sorted.sort(java.util.Comparator.comparing(JsonNode::toString));
                owners.removeAll();
                sorted.forEach(owners::add);
            }
            return record.substring(0, split + 1) + mapper.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String firstDifference(String expected, String actual) {
        int at = 0;
        while (at < expected.length() && at < actual.length() && expected.charAt(at) == actual.charAt(at)) at++;
        int from = Math.max(0, at - 120);
        return "at " + at + " expected …" + expected.substring(from, Math.min(expected.length(), at + 200))
                + "… actual …" + actual.substring(from, Math.min(actual.length(), at + 200)) + "…";
    }

    /** H2 JSON 열은 byte[]로 오므로 UTF-8 문자열로 바꿔 값으로 비교한다. */
    private List<Map<String, Object>> rows(String sql, Object... args) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(sql, args)) {
            Map<String, Object> converted = new java.util.LinkedHashMap<>();
            row.forEach((column, value) -> converted.put(column, value instanceof byte[] bytes
                    ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8) : value));
            result.add(converted);
        }
        return result;
    }

    private void assertStoredFormat(WorkerAnalysisJobPayload payload, boolean expectLive) {
        tx.executeWithoutResult(status -> assertThat(org.monitoring.catchholebackend.domain.analysis.processor.AnalysisAutomaticInputFormat
                .isLive(jobs.findById(payload.analysisJobId()).orElseThrow())).isEqualTo(expectLive));
    }

    private UUID publishWorld(WorkerAnalysisJobPayload payload, String subject, String setting, String value, String quote) {
        return tx.execute(status -> {
            AnalysisJob job = jobs.findById(payload.analysisJobId()).orElseThrow();
            WorldSettingCandidate candidate = WorldSettingCandidate.create(job.getWork(), job.getEpisode(), job, WorldSettingCategory.RACE,
                    subject, setting, value, evidence(quote), BigDecimal.ONE, null);
            entities.persist(candidate);
            job.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
            return candidate.getId();
        });
    }

    private void completeOnlyWorldBatch(WorkerAnalysisJobPayload payload, List<String> records, String label, UUID actualTarget, String setting, String value) {
        var batch = worldWorker.claimNextWorldSettingComparisonBatch(payload.analysisJobId(), payload.leaseToken()).orElseThrow();
        records.add("worldBatch" + label + " " + wire(batch));
        var context = worldWorker.getWorldSettingComparisonBatchContext(payload.analysisJobId(), batch.comparisonBatchId(), payload.leaseToken(),
                new WorkerWorldSettingComparisonBatchContextRequest(batch.resolvedTargetWorldSettingIds(), batch.resolvedProvisionalSubjectKeys()));
        records.add("worldContext" + label + " " + wire(context));
        var target = context.targets().getFirst();
        worldWorker.completeWorldSettingComparisonBatch(payload.analysisJobId(), batch.comparisonBatchId(), payload.leaseToken(),
                new WorkerWorldSettingComparisonBatchCompleteRequest(
                        List.of(new WorkerWorldSettingComparisonBatchCompleteRequest.ContextVersion(target.worldSettingId(), target.version(), target.provisionalSubjectKey())),
                        List.of(new WorkerWorldSettingComparisonBatchCompleteRequest.Decision("D1", List.of("C1"), "엘프", actualTarget,
                                null, null, List.of(), WorldSettingConsolidationStatus.SINGLE, WorldSettingSuggestedOperation.ADD, null,
                                null, setting, value, "원문 " + setting, Map.of(), target.provisionalSubjectKey())), Map.of(), context.contextToken()));
        assertThat(worldWorker.claimNextWorldSettingComparisonBatch(payload.analysisJobId(), payload.leaseToken())).isEmpty();
    }

    private WorkerAnalysisJobPayload claimWithSemantic() {
        return worker.claimAnalysisJob(new WorkerAnalysisJobClaimRequest("test-only", "검증", Set.of(AnalysisJobType.SETTING_EXTRACTION),
                Set.of(AnalysisMode.ORDERED_PROVISIONAL), null, null, null, true, true)).orElseThrow();
    }

    private String wire(Object value) {
        return wireJson.writeValueAsString(value);
    }

    /**
     * 두 실행의 식별자·시각·hash는 다르다(format 2의 inputStateHash는 저장 입력 형식이 달라 당연히 다르다). 기록 전체에서 처음 나온 순서대로
     * 같은 값은 같은 이름, 다른 값은 다른 이름으로 바꾼다. 그래서 한 실행 안에서 같은 hash·id를 가리키는 관계는 그대로 비교된다.
     */
    private static List<String> renamed(List<String> records) {
        java.util.regex.Pattern token = java.util.regex.Pattern.compile(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{64}|\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?");
        Map<String, String> names = new java.util.HashMap<>();
        List<String> result = new ArrayList<>();
        for (String record : records) {
            var matcher = token.matcher(record);
            StringBuilder renamedRecord = new StringBuilder();
            while (matcher.find()) {
                String name = names.computeIfAbsent(matcher.group(), value -> "<" + (value.length() == 64 ? "H" : value.contains("-") && value.length() == 36 ? "U" : "T") + names.size() + ">");
                matcher.appendReplacement(renamedRecord, java.util.regex.Matcher.quoteReplacement(name));
            }
            matcher.appendTail(renamedRecord);
            result.add(sortSemanticOwners(renamedRecord.toString()));
        }
        return result;
    }

    private Run runFor(int count, String email, String phone) {
        return tx.execute(status -> {
            Member member = Member.register(email, "test-only", phone, "검증 작가");
            entities.persist(member);
            Work work = Work.create(member, "격리 자동 분석", WorkGenre.FANTASY, "테스트");
            entities.persist(work);
            entities.persist(CharacterSettingSchema.create(work, "stats.mental", null, "정신",
                    CharacterFactType.STAT, SettingValueType.NUMBER, CharacterSettingValueSemantics.BASE_VALUE,
                    CharacterSettingMergePolicy.REPLACE, JSON.arrayNode(), CharacterSettingSchemaSource.SYSTEM_SEED, true));
            UploadBatch batch = UploadBatch.create(work, member, UploadType.MULTI_EPISODE_MULTI_FILE, UploadSourceType.FILE);
            entities.persist(batch);
            List<AnalysisJob> created = new ArrayList<>();
            for (int number = 1; number <= count; number++) {
                Episode episode = Episode.create(work, null, number, "원고", "automatic-test/" + number, "v1", storage.contentHash(SOURCE), SOURCE.codePointCount(0, SOURCE.length()));
                entities.persist(episode);
                AnalysisJob job = AnalysisJob.create(work, batch, episode, AnalysisJobType.SETTING_EXTRACTION);
                job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
                created.add(job);
            }
            states.initializeRun(created);
            return new Run(work.getId(), created.stream().map(AnalysisJob::getId).toList());
        });
    }

    // ---- #387 format 2: 실행이 처음 읽은 대상의 확정 근거 오류 ----

    @Test
    @DisplayName("#387 format 2는 claim에서 다른 인물의 근거를 검증하지 않고, 실행이 처음 읽은 인물의 근거 연결 오류는 Job을 claim 입력 실패 진단으로 마감해 재시도할 수 있게 한다")
    void liveInputEvidenceErrorOnFirstSubjectReadFailsJobCleanly() {
        org.junit.jupiter.api.Assumptions.assumeTrue(liveInput, "format 2 실행에서만 대상을 실행 중에 읽는다");
        Run run = run(2);
        WorkerAnalysisJobPayload first = claim();
        UUID broken = tx.execute(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            discovery(job, "비요른", null, true);
            SettingCandidate anchor = discovery(job, "에르웬", null, true);
            return addStat(job, anchor.getProvisionalSubjectKey(), null, 35, CharacterFactOperation.ADD).getId();
        });
        complete(first);
        UUID erwen = tx.execute(status -> entities.createQuery("select c.id from WorkCharacter c where c.work.id = :w and c.name = '에르웬'", UUID.class)
                .setParameter("w", run.workId()).getSingleResult());
        UUID bjorn = tx.execute(status -> entities.createQuery("select c.id from WorkCharacter c where c.work.id = :w and c.name = '비요른'", UUID.class)
                .setParameter("w", run.workId()).getSingleResult());
        // 에르웬의 현재 스탯 근거 후보가 다른 인물에 연결된 깨진 상태. claim 현재값은 slot 근거 사본을 만들지 않아 읽지 않는다.
        jdbc.update("update setting_candidates set matched_character_id=? where id=?", bjorn, broken);

        WorkerAnalysisJobPayload second = claim();
        assertThat(second.analysisJobId()).isEqualTo(run.jobs().getLast());
        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(second.analysisJobId()).orElseThrow();
            SettingCandidate candidate = SettingCandidate.create(job.getWork(), job.getEpisode(), null, job, SettingEntityType.CHARACTER,
                    "에르웬", "에르웬", erwen, SettingCandidateMatchStatus.MATCHED, "stats.mental", "36", SettingValueType.NUMBER,
                    JSON.objectNode().put("value", 36), evidence("stats.mental 수치는 36였다."), BigDecimal.ONE, null);
            entities.persist(candidate);
            job.updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_CANDIDATES_SAVED);
        });
        assertThatThrownBy(() -> {
            var batch = characterWorker.claimNextCharacterFactComparisonBatch(second.analysisJobId(), second.leaseToken()).orElseThrow();
            characterWorker.getCharacterFactComparisonBatchContext(second.analysisJobId(), batch.comparisonBatchId(), second.leaseToken());
        }).isInstanceOfSatisfying(AppException.class, error -> assertThat(error.getResultCode().getCode()).isEqualTo("ANALYSIS_CURRENT_EVIDENCE_INVALID"));

        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(second.analysisJobId()).orElseThrow();
            assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
            assertThat(job.getJournalStatus()).isEqualTo(AnalysisJournalStatus.INCOMPLETE);
            assertThat(job.getFailureCode()).isEqualTo(AnalysisFailureCode.UNEXPECTED_ERROR);
            assertThat(job.getFailureDiagnostics().stage()).isEqualTo("CLAIM_INPUT");
            assertThat(job.getFailureDiagnostics().reasonCode()).isEqualTo("CURRENT_EVIDENCE_BINDING_INVALID");
            assertThat(job.getFailureDiagnostics().sourceErrorCode()).isEqualTo("ANALYSIS_CURRENT_EVIDENCE_INVALID");
            assertThat(job.getLeaseToken()).isNull();
            assertThat(job.getEpisode().getStatus()).isEqualTo(org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.FAILED);
        });
        // 늦은 Worker 호출은 lease가 없어 거절되고, 같은 Job 재시도는 기존 경로로 접수된다.
        assertThatThrownBy(() -> worker.heartbeatAnalysisJob(second.analysisJobId(), second.leaseToken()))
                .isInstanceOf(AppException.class);
        Long owner = tx.execute(status -> entities.find(Work.class, run.workId()).getMember().getId());
        jdbc.update("update setting_candidates set matched_character_id=? where id=?", erwen, broken);
        publicJobs.retryFailedAnalysisJob(owner, run.workId(), second.analysisJobId());
        tx.executeWithoutResult(status -> assertThat(jobs.findById(second.analysisJobId()).orElseThrow().getStatus()).isEqualTo(AnalysisJobStatus.PENDING));
        WorkerAnalysisJobPayload retried = claim();
        assertThat(retried.analysisJobId()).isEqualTo(second.analysisJobId());
        var batch = characterWorker.claimNextCharacterFactComparisonBatch(retried.analysisJobId(), retried.leaseToken()).orElseThrow();
        assertThat(characterWorker.getCharacterFactComparisonBatchContext(retried.analysisJobId(), batch.comparisonBatchId(), retried.leaseToken())
                .snapshotEntries()).isNotEmpty();
    }

    @Test
    @DisplayName("#387 format 2 실행 중 GH201을 거치지 않은 확정 설정 쓰기(지문 불일치)는 그 Worker 호출을 409로 되돌린 뒤 실행을 무효화한다")
    void liveInputFingerprintMismatchInvalidatesRun() {
        org.junit.jupiter.api.Assumptions.assumeTrue(liveInput, "format 2 실행에서만 지문으로 실행 중 설정을 확인한다");
        Run run = run(2);
        WorkerAnalysisJobPayload first = claim();
        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            SettingCandidate anchor = discovery(job, "에르웬", null, true);
            addStat(job, anchor.getProvisionalSubjectKey(), null, 35, CharacterFactOperation.ADD);
        });
        complete(first);
        UUID erwen = tx.execute(status -> entities.createQuery("select c.id from WorkCharacter c where c.work.id = :w and c.name = '에르웬'", UUID.class)
                .setParameter("w", run.workId()).getSingleResult());
        WorkerAnalysisJobPayload second = claim();
        assertThat(second.analysisJobId()).isEqualTo(run.jobs().getLast());
        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(second.analysisJobId()).orElseThrow();
            SettingCandidate candidate = SettingCandidate.create(job.getWork(), job.getEpisode(), null, job, SettingEntityType.CHARACTER,
                    "에르웬", "에르웬", erwen, SettingCandidateMatchStatus.MATCHED, "stats.mental", "36", SettingValueType.NUMBER,
                    JSON.objectNode().put("value", 36), evidence("stats.mental 수치는 36였다."), BigDecimal.ONE, null);
            entities.persist(candidate);
            job.updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_CANDIDATES_SAVED);
        });
        // legacy 단건 비교 EXCLUDE가 대표 서사 시점을 바꿀 때처럼 GH201을 거치지 않고 snapshotVersion을 올린 쓰기다.
        jdbc.update("update characters set snapshot_version = snapshot_version + 1 where id=?", erwen);
        assertThatThrownBy(() -> {
            var batch = characterWorker.claimNextCharacterFactComparisonBatch(second.analysisJobId(), second.leaseToken()).orElseThrow();
            characterWorker.getCharacterFactComparisonBatchContext(second.analysisJobId(), batch.comparisonBatchId(), second.leaseToken());
        }).isInstanceOfSatisfying(AppException.class, error -> assertThat(error.getResultCode().getCode()).isEqualTo("ANALYSIS_RUN_STATE_CONFLICT"));
        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(second.analysisJobId()).orElseThrow();
            assertThat(job.getJournalStatus()).isEqualTo(AnalysisJournalStatus.INVALIDATED);
            assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.CANCELED);
            assertThat(job.getLeaseToken()).isNull();
        });
        assertThatThrownBy(() -> worker.heartbeatAnalysisJob(second.analysisJobId(), second.leaseToken())).isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("#387 format 2 claim이 읽는 인물의 대표 서사 시점 근거가 깨져 있으면 claim 트랜잭션을 되돌리지 않고 Job을 claim 입력 실패로 마감한다")
    void liveClaimNarrativeTimeEvidenceErrorCommitsClaimInputFailure() {
        org.junit.jupiter.api.Assumptions.assumeTrue(liveInput, "format 2 claim에서만 인물 현재값을 지금 DB에서 읽는다");
        Run run = run(2);
        UUID erwen = failSecondClaimOnBrokenNarrativeTime(run);
        // 마감이 커밋되어 같은 Job이 대기열 앞에서 claim 실패를 반복하지 않는다.
        assertThat(worker.claimAnalysisJob(request())).isEmpty();
        tx.executeWithoutResult(status -> ReflectionTestUtils.setField(entities.find(WorkCharacter.class, erwen), "currentNarrativeTime", null));
        Long owner = tx.execute(status -> entities.find(Work.class, run.workId()).getMember().getId());
        publicJobs.retryFailedAnalysisJob(owner, run.workId(), run.jobs().getLast());
        assertThat(claim().analysisJobId()).isEqualTo(run.jobs().getLast());
    }

    @Test
    @DisplayName("#387 format 2 재개 대기(INCOMPLETE) 실행도 지문이 바뀌면 재시도 409 뒤 무효화되어 확정 설정 수정을 계속 막지 않는다")
    void liveInputFingerprintMismatchInvalidatesIncompleteRunOnRetry() {
        org.junit.jupiter.api.Assumptions.assumeTrue(liveInput, "format 2 실행에서만 지문으로 재개 입력을 확인한다");
        Run run = run(2);
        UUID erwen = failSecondClaimOnBrokenNarrativeTime(run);
        tx.executeWithoutResult(status -> ReflectionTestUtils.setField(entities.find(WorkCharacter.class, erwen), "currentNarrativeTime", null));
        assertThatThrownBy(() -> states.assertSettingMutationAllowed(run.workId()))
                .isInstanceOfSatisfying(AppException.class, error -> assertThat(error.getResultCode().getCode()).isEqualTo("ANALYSIS_REVIEW_WAIT_REQUIRED"));
        // 재개 대기 중 GH201을 거치지 않은 쓰기(legacy 단건 비교 EXCLUDE 등)로 지문이 바뀐다.
        jdbc.update("update characters set snapshot_version = snapshot_version + 1 where id=?", erwen);
        Long owner = tx.execute(status -> entities.find(Work.class, run.workId()).getMember().getId());
        assertThatThrownBy(() -> publicJobs.retryFailedAnalysisJob(owner, run.workId(), run.jobs().getLast()))
                .isInstanceOfSatisfying(AppException.class, error -> assertThat(error.getResultCode().getCode()).isEqualTo("ANALYSIS_RUN_STATE_CONFLICT"));
        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(run.jobs().getLast()).orElseThrow();
            assertThat(job.getJournalStatus()).isEqualTo(AnalysisJournalStatus.INVALIDATED);
            assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        });
        states.assertSettingMutationAllowed(run.workId());
    }

    /** 1화에서 에르웬을 만든 뒤 대표 서사 시점이 없는 후보를 가리키게 깨고, 2화 claim을 claim 입력 실패(FAILED·INCOMPLETE)로 끝낸다. */
    private UUID failSecondClaimOnBrokenNarrativeTime(Run run) {
        WorkerAnalysisJobPayload first = claim();
        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            SettingCandidate anchor = discovery(job, "에르웬", null, true);
            addStat(job, anchor.getProvisionalSubjectKey(), null, 35, CharacterFactOperation.ADD);
        });
        complete(first);
        UUID erwen = tx.execute(status -> entities.createQuery("select c.id from WorkCharacter c where c.work.id = :w and c.name = '에르웬'", UUID.class)
                .setParameter("w", run.workId()).getSingleResult());
        tx.executeWithoutResult(status -> {
            WorkCharacter character = entities.find(WorkCharacter.class, erwen);
            assertThat(ReflectionTestUtils.getField(character, "currentNarrativeTime")).isNull();
            ReflectionTestUtils.setField(character, "currentNarrativeTime",
                    JSON.objectNode().put("episodeNo", 1).put("sourceCandidateId", UUID.randomUUID().toString()));
        });
        assertThat(worker.claimAnalysisJob(request())).isEmpty();
        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(run.jobs().getLast()).orElseThrow();
            assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
            assertThat(job.getJournalStatus()).isEqualTo(AnalysisJournalStatus.INCOMPLETE);
            assertThat(job.getFailureDiagnostics().stage()).isEqualTo("CLAIM_INPUT");
            assertThat(job.getFailureDiagnostics().reasonCode()).isEqualTo("CURRENT_EVIDENCE_BINDING_INVALID");
            assertThat(job.getLeaseToken()).isNull();
            assertThat(job.getInputStateHash()).hasSize(64);
        });
        return erwen;
    }

    private List<UUID> prepareWorldBatches(WorkerAnalysisJobPayload payload) {
        List<UUID> ids = tx.execute(status -> {
            AnalysisJob job = jobs.findById(payload.analysisJobId()).orElseThrow();
            job.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
            List<UUID> created = new ArrayList<>();
            LocalDateTime start = LocalDateTime.now().minusMinutes(1);
            for (int index = 0; index < 5; index++) {
                boolean failed = index < 4;
                WorldSettingCandidate candidate = WorldSettingCandidate.create(job.getWork(), job.getEpisode(), job,
                        WorldSettingCategory.RACE, failed ? "미궁" : "엘프", failed ? "설정" + index : "서식지",
                        failed ? "미확인 값" + index : "북부",
                        evidence(failed ? "미궁 원문 근거" : "엘프는 북부에 산다."), BigDecimal.ONE, null);
                entities.persist(candidate);
                entities.flush();
                ReflectionTestUtils.setField(candidate, "createdAt", start.plusSeconds(index));
                created.add(candidate.getId());
            }
            return created;
        });
        List<WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput> resolutions = new ArrayList<>();
        for (int index = 0; index < ids.size(); index++) {
            String key = "provisional-world:" + (index < 4 ? ids.getFirst() : ids.getLast());
            resolutions.add(new WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput(ids.get(index), List.of(), List.of(key), false));
        }
        worldWorker.resolveWorldSettingSubjects(payload.analysisJobId(), payload.leaseToken(), new WorkerWorldSettingSubjectResolutionRequest(resolutions));
        return ids;
    }

    private void completeWorldBatch(WorkerAnalysisJobPayload payload, WorkerWorldSettingComparisonBatchPayload batch) {
        var context = worldWorker.getWorldSettingComparisonBatchContext(payload.analysisJobId(), batch.comparisonBatchId(), payload.leaseToken(),
                new WorkerWorldSettingComparisonBatchContextRequest(batch.resolvedTargetWorldSettingIds(), batch.resolvedProvisionalSubjectKeys()));
        var target = context.targets().getFirst();
        var completion = new WorkerWorldSettingComparisonBatchCompleteRequest(
                List.of(new WorkerWorldSettingComparisonBatchCompleteRequest.ContextVersion(target.worldSettingId(), target.version(), target.provisionalSubjectKey())),
                List.of(new WorkerWorldSettingComparisonBatchCompleteRequest.Decision("D1", List.of("C1"), "엘프", null,
                        null, null, List.of(), WorldSettingConsolidationStatus.SINGLE, WorldSettingSuggestedOperation.ADD, null,
                        null, "서식지", "북부", "원문 서식지", Map.of(), target.provisionalSubjectKey())), Map.of(), context.contextToken());
        worldWorker.completeWorldSettingComparisonBatch(payload.analysisJobId(), batch.comparisonBatchId(), payload.leaseToken(), completion);
        worldWorker.completeWorldSettingComparisonBatch(payload.analysisJobId(), batch.comparisonBatchId(), payload.leaseToken(), completion);
    }

    private void completeCharacterBatch(WorkerAnalysisJobPayload payload, UUID batchId) {
        var context = characterWorker.getCharacterFactComparisonBatchContext(payload.analysisJobId(), batchId, payload.leaseToken());
        var completion = new WorkerCharacterFactComparisonBatchCompleteRequest(context.contextToken(),
                List.of(new WorkerCharacterFactComparisonBatchCompleteRequest.Decision("C1", CharacterFactOperation.ADD,
                        "stats.mental", null, List.of(), List.of(), "35", Map.of("value", 35),
                        CharacterFactTemporalScope.PRESENT, "원문 수치", Map.of())), List.of(), Map.of());
        characterWorker.completeCharacterFactComparisonBatch(payload.analysisJobId(), batchId, payload.leaseToken(), completion);
        characterWorker.completeCharacterFactComparisonBatch(payload.analysisJobId(), batchId, payload.leaseToken(), completion);
    }

    private List<UUID> prepareCharacterBatches(WorkerAnalysisJobPayload payload) {
        return tx.execute(status -> {
            AnalysisJob job = jobs.findById(payload.analysisJobId()).orElseThrow();
            List<UUID> created = new ArrayList<>();
            LocalDateTime start = LocalDateTime.now().minusMinutes(1);
            for (int index = 0; index < 2; index++) {
                String name = index == 0 ? "비요른" : "에르웬";
                SettingCandidate discovered = discovery(job, name, null, true);
                int value = index == 0 ? 99 : 35;
                SettingCandidate candidate = SettingCandidate.create(job.getWork(), job.getEpisode(), null, job,
                        SettingEntityType.CHARACTER, name, "stats.mental", String.valueOf(value), SettingValueType.NUMBER,
                        JSON.objectNode().put("value", value), evidence(name + "의 정신 " + value), BigDecimal.ONE, null);
                ReflectionTestUtils.setField(candidate, "provisionalSubjectKey", discovered.getProvisionalSubjectKey());
                entities.persist(candidate);
                entities.flush();
                ReflectionTestUtils.setField(candidate, "createdAt", start.plusSeconds(index));
                created.add(candidate.getId());
            }
            job.updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_CANDIDATES_SAVED);
            return created;
        });
    }

    private void failPreparedCharacterComparison(AnalysisJob job, SettingCandidate candidate, String message) {
        JsonNode target = characterStates.getTarget(job, candidate.getMatchedCharacterId(), candidate.getProvisionalSubjectKey());
        CharacterFactComparisonBatch batch = CharacterFactComparisonBatch.createProvisional(job.getWork(), job.getEpisode(), job,
                candidate.getProvisionalSubjectKey(), CharacterFactType.STAT, 1);
        batch.recordAnalysisContext(target);
        entities.persist(batch);
        candidate.startComparison(batch, "C1");
        candidate.failComparison(AnalysisFailureCode.LLM_RESPONSE_PARSE_ERROR, message);
        batch.fail(AnalysisFailureCode.LLM_RESPONSE_PARSE_ERROR, message);
    }

    private Run run(int count) { return run(count, AnalysisReviewMode.AUTOMATIC); }

    private Run run(int count, AnalysisReviewMode mode) {
        return tx.execute(status -> {
            Member member = Member.register("automatic@example.invalid", "test-only", "01012345678", "검증 작가");
            entities.persist(member);
            Work work = Work.create(member, "격리 자동 분석", WorkGenre.FANTASY, "테스트");
            entities.persist(work);
            CharacterSettingSchema schema = CharacterSettingSchema.create(work, "stats.mental", null, "정신",
                    CharacterFactType.STAT, SettingValueType.NUMBER, CharacterSettingValueSemantics.BASE_VALUE,
                    CharacterSettingMergePolicy.REPLACE, JSON.arrayNode(), CharacterSettingSchemaSource.SYSTEM_SEED, true);
            entities.persist(schema);
            UploadBatch batch = UploadBatch.create(work, member, UploadType.MULTI_EPISODE_MULTI_FILE, UploadSourceType.FILE);
            entities.persist(batch);
            List<AnalysisJob> created = new ArrayList<>();
            for (int number = 1; number <= count; number++) {
                Episode episode = Episode.create(work, null, number, "원고", "automatic-test/" + number, "v1", storage.contentHash(SOURCE), SOURCE.codePointCount(0, SOURCE.length()));
                entities.persist(episode);
                AnalysisJob job = AnalysisJob.create(work, batch, episode, AnalysisJobType.SETTING_EXTRACTION);
                job.configureReviewMode(mode);
                created.add(job);
            }
            states.initializeRun(created);
            return new Run(work.getId(), created.stream().map(AnalysisJob::getId).toList());
        });
    }

    private SettingCandidate discovery(AnalysisJob job, String name, UUID actualId, boolean provisional) {
        SettingCandidate candidate = SettingCandidate.createCharacterDiscovery(job.getWork(), job.getEpisode(), null, job,
                name, name, actualId, actualId == null ? SettingCandidateMatchStatus.UNRESOLVED : SettingCandidateMatchStatus.MATCHED,
                evidence(name + "라고 말했다."), BigDecimal.ONE, null);
        entities.persist(candidate);
        if (provisional) ReflectionTestUtils.setField(candidate, "provisionalSubjectKey", CharacterAnalysisStateMapper.provisionalRef(candidate.getId()));
        entities.flush();
        characterStates.prepareProvisionalCandidates(job);
        return candidate;
    }

    private SettingCandidate addStat(AnalysisJob job, String provisional, UUID actualId, int value, CharacterFactOperation operation) {
        return addStat(job, provisional, actualId, "stats.mental", value, operation);
    }

    private SettingCandidate addStat(AnalysisJob job, String provisional, UUID actualId, String factKey, int value,
            CharacterFactOperation operation) {
        SettingCandidate candidate = SettingCandidate.create(job.getWork(), job.getEpisode(), null, job,
                SettingEntityType.CHARACTER, "에르웬", "에르웬", actualId,
                actualId == null ? SettingCandidateMatchStatus.UNRESOLVED : SettingCandidateMatchStatus.MATCHED,
                factKey, String.valueOf(value), SettingValueType.NUMBER, JSON.objectNode().put("value", value),
                evidence(factKey + " 수치는 " + value + "였다."), BigDecimal.ONE, null);
        if (provisional != null) ReflectionTestUtils.setField(candidate, "provisionalSubjectKey", provisional);
        entities.persist(candidate);
        entities.flush();
        characterStates.prepareProvisionalCandidates(job);
        JsonNode target = characterStates.getTarget(job, actualId, provisional);
        CharacterFactComparisonBatch batch = provisional != null
                ? CharacterFactComparisonBatch.createProvisional(job.getWork(), job.getEpisode(), job, provisional, CharacterFactType.STAT, 1)
                : CharacterFactComparisonBatch.create(job.getWork(), job.getEpisode(), job, entities.find(WorkCharacter.class, actualId), CharacterFactType.STAT, 1, target.path("snapshotVersion").asLong());
        ObjectNode comparisonContext = JSON.objectNode();
        var slots = comparisonContext.putArray("slots");
        target.path("slots").forEach(slot -> slots.add(slot.deepCopy()));
        batch.recordAnalysisContext(comparisonContext);
        entities.persist(batch);
        candidate.startComparison(batch, "C1");
        candidate.recordComparisonContext(batch.getBaseSnapshotVersion(), job.getInputStateHash());
        candidate.completeComparison(operation, CharacterFactType.STAT, factKey, String.valueOf(value), JSON.objectNode().put("value", value),
                JSON.arrayNode(), CharacterFactTemporalScope.PRESENT, "원문 수치", JSON.objectNode(), LocalDateTime.now(), factKey, JSON.arrayNode());
        batch.complete("b".repeat(64), JSON.objectNode());
        characterStates.recordDecision(job, candidate, new CharacterSnapshotEntry(new CharacterSnapshotSlot(CharacterFactType.STAT, factKey),
                String.valueOf(value), JSON.objectNode().put("value", value), true), List.of(), List.of());
        return candidate;
    }

    private void addWorld(AnalysisJob job) {
        WorldSettingCandidate candidate = WorldSettingCandidate.create(job.getWork(), job.getEpisode(), job,
                WorldSettingCategory.RACE, "엘프", "서식지", "북부", JSON.arrayNode(), BigDecimal.ONE, null);
        entities.persist(candidate);
        String key = "provisional-world:" + candidate.getId();
        candidate.resolveOrderedSubject(WorldSettingSubjectResolutionType.NEW, key, "엘프", JSON.arrayNode(), JSON.arrayNode().add(key));
        WorldSettingComparisonBatch batch = WorldSettingComparisonBatch.createOrdered(job.getWork(), job.getEpisode(), job,
                WorldSettingCategory.RACE, null, WorldSettingSubjectResolutionType.NEW, key, "엘프", JSON.arrayNode(), JSON.arrayNode().add(key), 1);
        entities.persist(batch);
        candidate.startComparison(batch, "C1");
        WorldSettingComparisonDecision decision = WorldSettingComparisonDecision.create(batch, "D1", "엘프", null, null, null,
                WorldSettingConsolidationStatus.SINGLE, WorldSettingSuggestedOperation.ADD, null, null, "서식지", null, "북부", "원문 근거", null);
        decision.bindOrderedTarget(key, 0L);
        entities.persist(decision);
        candidate.completeComparison(decision, LocalDateTime.now());
        entities.persist(WorldSettingComparisonDecisionSource.create(batch, decision, candidate, "C1", 0));
        batch.complete("c".repeat(64), JSON.objectNode());
        ObjectNode target = JSON.objectNode();
        target.putNull("actualWorldSettingId");
        target.put("provisionalSubjectKey", key);
        target.put("category", "RACE");
        target.put("subjectName", "엘프");
        target.put("normalizedSubjectName", "엘프");
        target.put("version", 1);
        target.putObject("propertiesJson").put("서식지", "북부");
        target.putObject("provenanceByPath");
        states.appendValidatedChanges(job, job.getInputStateHash(), List.of(new AnalysisStateChange("world-decision:" + decision.getId(),
                List.of("worldSettings", key), target, false, "ADD", List.of(candidate.getId()))));
    }

    private WorkerAnalysisJobPayload claim() { return worker.claimAnalysisJob(request()).orElseThrow(); }
    private WorkerAnalysisJobClaimRequest request() {
        return new WorkerAnalysisJobClaimRequest("test-only", "검증", Set.of(AnalysisJobType.SETTING_EXTRACTION), Set.of(AnalysisMode.ORDERED_PROVISIONAL), null, null, null, null, true);
    }
    private JsonNode input(WorkerAnalysisJobPayload payload) {
        return tx.execute(status -> states.getInputState(jobs.findById(payload.analysisJobId()).orElseThrow()).deepCopy());
    }
    private JsonNode storedInput(WorkerAnalysisJobPayload payload) {
        return tx.execute(status -> jobs.findById(payload.analysisJobId()).orElseThrow().getAutomaticInputState().deepCopy());
    }
    /** 실행 뒤에도 보존되는 입력: format 1은 claim 사본 전체, format 2는 저장 입력(지문·references)이다. */
    private JsonNode preservedInput(WorkerAnalysisJobPayload payload) {
        return liveInput ? storedInput(payload) : input(payload);
    }
    private void complete(WorkerAnalysisJobPayload payload) {
        tx.executeWithoutResult(status -> {
            jobs.findById(payload.analysisJobId()).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED);
            worker.completeAnalysisJob(payload.analysisJobId(), payload.leaseToken(), new WorkerAnalysisJobCompleteRequest("{}", null, null));
        });
    }
    private record Run(UUID workId, List<UUID> jobs) { }
}
