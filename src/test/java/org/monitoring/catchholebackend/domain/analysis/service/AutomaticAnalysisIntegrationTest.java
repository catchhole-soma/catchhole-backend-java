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
    @Autowired EntityManager entities;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AnalysisJobRepository jobs;
    @Autowired SettingCandidateRepository candidates;
    @Autowired AnalysisRunStateService states;
    @Autowired AnalysisJobService publicJobs;
    @Autowired CharacterAnalysisStateService characterStates;
    @Autowired SettingCandidateService characterReview;
    @Autowired AnalysisJobWorkerService worker;
    @Autowired WorldSettingWorkerService worldWorker;
    @Autowired WorldSettingService worldSettings;
    @Autowired CharacterFactComparisonWorkerService characterWorker;
    @MockitoSpyBean WorldSettingCandidateServiceImpl worldApplication;
    private TransactionTemplate tx;

    @BeforeEach
    void prepareTransactions() { tx = new TransactionTemplate(transactions); }

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
        JsonNode originalInput = input(first);
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
        assertThat(input(first)).isEqualTo(originalInput);
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
                    JSON.arrayNode().add(JSON.objectNode().put("quote", "에르웬의 본명은 에르웬 미샤다.")
                            .put("startOffset", 20).put("endOffset", 39)), BigDecimal.ONE, null);
            ReflectionTestUtils.setField(anchor, "evidenceSpans", JSON.arrayNode().add(JSON.objectNode()
                    .put("quote", "에르웬이 등장했다.").put("startOffset", 0).put("endOffset", 10)));
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
                    JSON.objectNode().put("value", 99), JSON.arrayNode().add(JSON.objectNode().put("quote", "정신 수치는 99였다.")), BigDecimal.ONE, null);
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
        assertThat(input.path("characters").findValuesAsText("factValue")).doesNotContain("99");
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
        JsonNode frozen = input(second);
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
                    "v1", "a".repeat(64), 10);
            entities.persist(episode);
            AnalysisJob job = AnalysisJob.create(work, batch, episode, AnalysisJobType.SETTING_EXTRACTION);
            job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
            states.initializeRun(List.of(job));
            return job.getId();
        });
    }

    @ParameterizedTest
    @MethodSource("lateCharacterCases")
    @DisplayName("캐릭터 늦은 확정은 최신값·제거·수동값·과거 상태를 보호하고 빈 항목만 추가한다")
    void lateCharacterConfirmationKeepsLaterFactAndFinishedRun(String scenario, boolean group) {
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
        UUID batch = tx.execute(status -> jobs.findById(first.analysisJobId()).orElseThrow().getBatch().getId());
        if (group) {
            var omitted = new SettingCandidateGroupConfirmRequest(batch, List.of(new SettingCandidateGroupConfirmDecision(
                    pending, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, null, false)));
            assertThatThrownBy(() -> characterReview.confirmSettingCandidateGroup(owner, run.workId(), omitted))
                    .isInstanceOfSatisfying(AppException.class, error -> assertThat(error.getResultCode())
                            .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_EDIT_APPLICATION_REQUIRED));
            var request = new SettingCandidateGroupConfirmRequest(batch, List.of(new SettingCandidateGroupConfirmDecision(
                    pending, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, null, true)));
            assertThat(characterReview.confirmSettingCandidateGroup(owner, run.workId(), request).recomparisonRequired()).isFalse();
            nextBatch(run.workId(), 3);
            assertThat(characterReview.confirmSettingCandidateGroup(owner, run.workId(), request).recomparisonRequired()).isFalse();
        } else {
            var omitted = new SettingCandidateConfirmRequest(
                    CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, null, false);
            assertThatThrownBy(() -> characterReview.confirmSettingCandidate(owner, run.workId(), pending, omitted))
                    .isInstanceOfSatisfying(AppException.class, error -> assertThat(error.getResultCode())
                            .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_EDIT_APPLICATION_REQUIRED));
            var request = new SettingCandidateConfirmRequest(CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, null, true);
            assertThat(characterReview.confirmSettingCandidate(owner, run.workId(), pending, request).recomparisonRequired()).isFalse();
            nextBatch(run.workId(), 3);
            assertThat(characterReview.confirmSettingCandidate(owner, run.workId(), pending, request).recomparisonRequired()).isFalse();
        }
        tx.executeWithoutResult(status -> {
            assertThat(candidates.findById(pending).orElseThrow().getConfirmedApplicationMode()).isEqualTo(scenario.equals("empty")
                    ? CharacterFactConfirmApplicationMode.APPLY_PROPOSAL : CharacterFactConfirmApplicationMode.HISTORY_ONLY);
            if (scenario.equals("past")) assertThat(candidates.findById(pending).orElseThrow().getTemporalScope()).isEqualTo(CharacterFactTemporalScope.PAST);
            assertThat(jobs.findById(second.analysisJobId()).orElseThrow().getJournalStatus()).isEqualTo(AnalysisJournalStatus.SEALED);
            assertThat(jobs.findById(second.analysisJobId()).orElseThrow().getStatus()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
            assertThat(entities.createQuery("select count(f) from CharacterFact f where f.settingCandidate.id = :candidate", Long.class)
                    .setParameter("candidate", pending).getSingleResult()).isEqualTo(1);
        });
        var third = claim();
        var values = input(third).path("characters").findValuesAsText("factValue");
        if (scenario.equals("empty")) assertThat(values).contains("35");
        else { assertThat(values).doesNotContain("35");
            if (scenario.equals("latest")) assertThat(values).contains("36");
            if (scenario.equals("manual")) assertThat(values).contains("88");
        }
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
    @DisplayName("완료된 회차의 같은 캐릭터 항목을 묶어 확정하면 앞 후보만 현재값이 되고 뒤 후보는 이력에 남는다")
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

        assertThat(characterReview.confirmSettingCandidateGroup(owner, run.workId(), request).recomparisonRequired())
                .isFalse();

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
                JSON.objectNode().put("value", 99), JSON.arrayNode().add(JSON.objectNode().put("quote", "정신 수치는 99였다.")),
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
                    JSON.arrayNode().add(JSON.objectNode().put("quote", "데이비스는 기절했지만 치료 후 깨어났다.")),
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
    @DisplayName("원문 파기는 다음 회차에 복사된 근거와 보류 정보 및 새 실행 시작 snapshot의 근거를 지운다")
    void purgesCopiedIdentityEvidenceAndPendingReferences() {
        Run run = run(2);
        WorkerAnalysisJobPayload first = claim();
        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            discovery(job, "에르웬", null, true);
            SettingCandidate pending = SettingCandidate.create(job.getWork(), job.getEpisode(), null, job,
                    SettingEntityType.CHARACTER, "미상", "stats.mental", "99", SettingValueType.NUMBER,
                    JSON.objectNode().put("value", 99), JSON.arrayNode().add(JSON.objectNode().put("quote", "파기할 보류 근거")), BigDecimal.ONE, null);
            entities.persist(pending);
        });
        complete(first);
        WorkerAnalysisJobPayload second = claim();
        assertThat(input(second).toString()).contains("에르웬라고 말했다.", "파기할 보류 근거");
        UUID freshRunRoot = tx.execute(status -> {
            AnalysisJob next = jobs.findById(second.analysisJobId()).orElseThrow();
            AnalysisJob fresh = AnalysisJob.create(next.getWork(), next.getBatch(), next.getEpisode(), AnalysisJobType.SETTING_EXTRACTION);
            fresh.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
            states.initializeRun(List.of(fresh));
            assertThat(fresh.getRunBaseState().toString()).contains("에르웬라고 말했다.");
            return fresh.getId();
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
            assertThat(jobs.findById(freshRunRoot).orElseThrow().getRunBaseState().toString()).doesNotContain("에르웬라고 말했다.");
        });
    }

    @Test
    @DisplayName("앞 원문 파기 후에도 완료된 후행 후보는 최신값을 보호하며 추가 분석 없이 확정한다")
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
        characterReview.updateSettingCandidate(owner, run.workId(), pending.getFirst(), new SettingCandidateUpdateRequest("stats.mental", "35"));
        assertThat(characterReview.confirmSettingCandidate(owner, run.workId(), pending.getFirst(),
                new SettingCandidateConfirmRequest(CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, null, true)).recomparisonRequired()).isFalse();
        UUID batch = tx.execute(status -> jobs.findById(second.analysisJobId()).orElseThrow().getBatch().getId());
        worldApplication.updateCandidateDecisions(owner, run.workId(), new WorldSettingCandidateDecisionUpdateRequest(batch,
                List.of(new WorldSettingCandidateDecisionUpdateItem(pending.get(1), WorldSettingOperation.ADD,
                        WorldSettingCategory.RACE, "엘프", null, "서식지", "남부", "직접 확인"))));
        assertThat(worldApplication.confirmCandidate(owner, run.workId(), pending.get(1),
                new WorldSettingCandidateConfirmRequest(WorldSettingOperation.ADD, WorldSettingCategory.RACE,
                        "엘프", null, "서식지", "남부", true, "직접 확인")).recomparisonRequired()).isFalse();
        tx.executeWithoutResult(status -> {
            assertThat(candidates.findById(pending.getFirst()).orElseThrow().getConfirmedApplicationMode())
                    .isEqualTo(CharacterFactConfirmApplicationMode.HISTORY_ONLY);
            assertThat(entities.find(WorldSettingCandidate.class, pending.get(1)).isHistoryOnly()).isTrue();
            assertThat(jobs.count()).isEqualTo(3);
            assertThat(jobs.findById(second.analysisJobId()).orElseThrow().isCompletedOrderedAnalysis()).isTrue();
            assertThat(jobs.findById(third.analysisJobId()).orElseThrow().isCompletedOrderedAnalysis()).isTrue();
        });
        nextBatch(run.workId(), 4);
        var current = input(claim());
        assertThat(current.path("characters").findValuesAsText("factValue")).contains("36").doesNotContain("35");
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
                    JSON.objectNode().put("value", 99), JSON.arrayNode().add(JSON.objectNode().put("quote", "정신 수치는 99였다.")), BigDecimal.ONE, null);
            ReflectionTestUtils.setField(failedCharacter, "provisionalSubjectKey", discovery.getProvisionalSubjectKey());
            entities.persist(failedCharacter);
            entities.flush();
            characterStates.prepareProvisionalCandidates(job);
            failPreparedCharacterComparison(job, failedCharacter, "개별 비교 해석 실패");
            WorldSettingCandidate failedWorld = WorldSettingCandidate.create(job.getWork(), job.getEpisode(), job,
                    WorldSettingCategory.RACE, "판단 실패 종족", "수명", "미확인 값", JSON.arrayNode().add(JSON.objectNode().put("quote", "해당 종족의 수명을 알 수 없다.")), BigDecimal.ONE, null);
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
                    "엘프", "서식지", "북부", JSON.arrayNode(), BigDecimal.ONE, null);
            var failed = WorldSettingCandidate.create(job.getWork(), job.getEpisode(), job, WorldSettingCategory.RACE,
                    "엘프", "수명", "미확인 수명", JSON.arrayNode().add(JSON.objectNode().put("quote", "엘프의 수명을 알 수 없다.")), BigDecimal.ONE, null);
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
            var response = new org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingMapper().toCandidateResponse(failed);
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
                    JSON.objectNode().put("value", 99), JSON.arrayNode().add(JSON.objectNode().put("quote", "정신 수치는 99였다.")), BigDecimal.ONE, null);
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
        characterReview.updateSettingCandidate(ownerId, run.workId(), failedId, new SettingCandidateUpdateRequest("stats.mental", "99"));
        UUID batchId = tx.execute(status -> jobs.findById(first.analysisJobId()).orElseThrow().getBatch().getId());
        tx.executeWithoutResult(status -> {
            WorkCharacter character = entities.createQuery("select c from WorkCharacter c where c.work.id = :id", WorkCharacter.class)
                    .setParameter("id", run.workId()).getSingleResult();
            assertThat(character.getSnapshotVersion()).isPositive();
        });
        if (groupConfirm) {
            var result = characterReview.confirmSettingCandidateGroup(ownerId, run.workId(),
                    new SettingCandidateGroupConfirmRequest(batchId, List.of(new SettingCandidateGroupConfirmDecision(
                            failedId, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, 0L, true))));
            assertThat(result.recomparisonRequired()).isFalse();
        } else {
            var result = characterReview.confirmSettingCandidate(ownerId, run.workId(), failedId,
                    new SettingCandidateConfirmRequest(CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, 0L, true));
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
                        JSON.arrayNode().add(JSON.objectNode().put("quote", failed ? "미궁 원문 근거" : "엘프는 북부에 산다.")), BigDecimal.ONE, null);
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
                        JSON.objectNode().put("value", value), JSON.arrayNode().add(JSON.objectNode().put("quote", name + "의 정신 " + value)), BigDecimal.ONE, null);
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
                Episode episode = Episode.create(work, null, number, "원고", "automatic-test/" + number, "v1", "a".repeat(64), 10);
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
                JSON.arrayNode().add(JSON.objectNode().put("quote", name + "라고 말했다.")), BigDecimal.ONE, null);
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
                JSON.arrayNode().add(JSON.objectNode().put("quote", factKey + " 수치는 " + value + "였다.")), BigDecimal.ONE, null);
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
        return new WorkerAnalysisJobClaimRequest("test-only", "검증", Set.of(AnalysisJobType.SETTING_EXTRACTION), Set.of(AnalysisMode.ORDERED_PROVISIONAL));
    }
    private JsonNode input(WorkerAnalysisJobPayload payload) {
        return tx.execute(status -> states.getInputState(jobs.findById(payload.analysisJobId()).orElseThrow()).deepCopy());
    }
    private void complete(WorkerAnalysisJobPayload payload) {
        tx.executeWithoutResult(status -> {
            jobs.findById(payload.analysisJobId()).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED);
            worker.completeAnalysisJob(payload.analysisJobId(), payload.leaseToken(), new WorkerAnalysisJobCompleteRequest("{}", null, null));
        });
    }
    private record Run(UUID workId, List<UUID> jobs) { }
}
