package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.dto.request.AnalysisJobCreateRequest;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisBatchMapper;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisJobMapper;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeSourcePurgeRequestRepository;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("누적 분석 작업의 명시적 실패 재시도")
class AnalysisJobOrderedRetryTest {

    private static final long MEMBER_ID = 19L;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Mock private AnalysisRunStateService runStateService;
    @Mock private AnalysisJobRepository jobRepository;
    @Mock private WorkRepository workRepository;
    @Mock private UploadBatchRepository batchRepository;
    @Mock private UploadFileRepository fileRepository;
    @Mock private AnalysisBatchMapper batchMapper;
    @Mock private EpisodeRepository episodeRepository;
    @Mock private EpisodeSourcePurgeRequestRepository purgeRepository;
    @Mock private SettingCandidateRepository characterRepository;
    @Mock private WorldSettingCandidateRepository worldRepository;
    @Mock private AiTokenService tokenService;
    private AnalysisJobServiceImpl service;
    private Work work;
    private Episode episode;
    private AnalysisJob job;

    @BeforeEach
    void setUp() {
        service = new AnalysisJobServiceImpl(runStateService, jobRepository, workRepository,
                batchRepository, fileRepository, new AnalysisJobMapper(), batchMapper,
                episodeRepository, purgeRepository, characterRepository, worldRepository, tokenService);
        Member member = Member.register("retry@example.com", "password", "01012345678", "작가");
        ReflectionTestUtils.setField(member, "id", MEMBER_ID);
        work = Work.create(member, "누적 작품", WorkGenre.FANTASY, "설명");
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        episode = Episode.create(work, UUID.randomUUID(), 11, "11화", "source/11.txt", "v1", "a".repeat(64), 100);
        ReflectionTestUtils.setField(episode, "id", UUID.randomUUID());
        job = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(job, "id", UUID.randomUUID());
        JsonNode base = objectMapper.createObjectNode()
                .setAll(java.util.Map.of("characters", objectMapper.createObjectNode(),
                        "worldSettings", objectMapper.createObjectNode(), "references", objectMapper.createObjectNode()));
        job.initializeOrderedRun(UUID.randomUUID(), 1, 0, null, base);
        job.prepareOrderedInput("b".repeat(64));
        job.replacePendingJournal(objectMapper.createObjectNode().put("inputStateHash", "b".repeat(64))
                .set("changes", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode().put("eventId", "completed-prefix"))));
        job.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
        job.claim("saved-model", "비교 중", LocalDateTime.now().plusMinutes(1));
        job.fail(AnalysisFailureCode.UNEXPECTED_ERROR, "처리 실패", 120, 40);
    }

    @Test
    @DisplayName("완료 prefix와 입력·실행·checkpoint·사용 토큰을 보존하고 실패 후보만 재개한다")
    void preservesRunAndCompletedPrefix() {
        bindRetryLookup();
        UUID runId = job.getAnalysisRunId();
        JsonNode oldJournal = job.getStateJournal();
        JsonNode oldBase = job.getRunBaseState();
        SettingCandidate character = mock(SettingCandidate.class);
        WorldSettingCandidate world = mock(WorldSettingCandidate.class);
        UUID characterId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        when(character.getId()).thenReturn(characterId);
        when(world.getId()).thenReturn(worldId);
        when(characterRepository.findAllByAnalysisJobIdAndComparisonStatus(job.getId(), CharacterFactComparisonStatus.FAILED))
                .thenReturn(List.of(character));
        when(worldRepository.findAllByAnalysisJobIdAndComparisonStatus(job.getId(), WorldSettingComparisonStatus.FAILED))
                .thenReturn(List.of(world));
        when(characterRepository.findByIdAndWorkIdForUpdate(characterId, work.getId())).thenReturn(Optional.of(character));
        when(worldRepository.findByIdAndWorkIdForUpdate(worldId, work.getId())).thenReturn(Optional.of(world));

        var response = service.retryFailedAnalysisJob(MEMBER_ID, work.getId(), job.getId());

        assertThat(response).singleElement().satisfies(saved -> {
            assertThat(saved.id()).isEqualTo(job.getId());
            assertThat(saved.status()).isEqualTo(AnalysisJobStatus.PENDING);
            assertThat(saved.analysisRun().runId()).isEqualTo(runId);
        });
        assertThat(job.getAnalysisMode()).isEqualTo(AnalysisMode.ORDERED_PROVISIONAL);
        assertThat(job.getRunGeneration()).isEqualTo(1L);
        assertThat(job.getRunSequence()).isZero();
        assertThat(job.getTargetEpisodes()).containsExactly(episode);
        assertThat(job.getStateJournal()).isEqualTo(oldJournal);
        assertThat(job.getRunBaseState()).isEqualTo(oldBase);
        assertThat(job.getInputStateHash()).isEqualTo("b".repeat(64));
        assertThat(job.getSourceContentHash()).isEqualTo("a".repeat(64));
        assertThat(job.getSourceEpisodeNo()).isEqualTo(11);
        assertThat(job.getCheckpointStage()).isEqualTo(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
        assertThat(job.getInputTokenCount()).isEqualTo(120);
        assertThat(job.getOutputTokenCount()).isEqualTo(40);
        assertThat(job.getClaimAttemptCount()).isZero();
        assertThat(job.getLeaseToken()).isNull();
        assertThat(job.getErrorMessage()).isNull();
        assertThat(job.getCompletedAt()).isNull();
        verify(character).retryFailedOrderedComparison();
        verify(world).retryFailedOrderedComparison();
        verify(jobRepository, never()).save(any());
        verify(characterRepository, never()).deleteAll(any());
        verify(worldRepository, never()).deleteAll(any());
        InOrder order = inOrder(workRepository, jobRepository, runStateService, tokenService, characterRepository, character);
        order.verify(workRepository).getOwnedWorkForUpdate(work.getId(), MEMBER_ID);
        order.verify(jobRepository).findByIdForUpdate(job.getId());
        order.verify(runStateService).validateResume(job);
        order.verify(tokenService).ensureAnalysisCanStart(MEMBER_ID);
        order.verify(characterRepository).findByIdAndWorkIdForUpdate(characterId, work.getId());
        order.verify(character).retryFailedOrderedComparison();
    }

    @Test
    @DisplayName("SUCCEEDED라도 INCOMPLETE 변경 기록은 동일 작업에서 재개한다")
    void resumesIncompleteSuccess() {
        job.markJournalIncomplete();
        job.succeed("{\"failedCount\":1}", 120, 40);
        job.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED);
        bindRetryLookup();

        service.retryFailedAnalysisJob(MEMBER_ID, work.getId(), job.getId());

        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.PENDING);
        assertThat(job.getJournalStatus()).isEqualTo(AnalysisJournalStatus.PENDING);
        assertThat(job.getSummaryJson()).isNull();
        assertThat(job.getCheckpointStage()).isEqualTo(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED);
    }

    @Test
    @DisplayName("저장 장애 후 재개해도 이미 보류한 준비 실패와 불변 참고 기록은 다시 비교하지 않는다")
    void resumePreservesDeferredPreparationFailuresAndTheirJournal() {
        bindRetryLookup();
        ReflectionTestUtils.setField(job, "reviewMode",
                org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.AUTOMATIC);
        SettingCandidate character = mock(SettingCandidate.class);
        WorldSettingCandidate world = mock(WorldSettingCandidate.class);
        UUID characterId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        when(character.getId()).thenReturn(characterId);
        when(world.getId()).thenReturn(worldId);
        when(character.canDeferFailedComparison()).thenReturn(true);
        when(world.canDeferFailedComparison()).thenReturn(true);
        when(characterRepository.findAllByAnalysisJobIdAndComparisonStatus(job.getId(), CharacterFactComparisonStatus.FAILED))
                .thenReturn(List.of(character));
        when(worldRepository.findAllByAnalysisJobIdAndComparisonStatus(job.getId(), WorldSettingComparisonStatus.FAILED))
                .thenReturn(List.of(world));
        when(characterRepository.findByIdAndWorkIdForUpdate(characterId, work.getId())).thenReturn(Optional.of(character));
        when(worldRepository.findByIdAndWorkIdForUpdate(worldId, work.getId())).thenReturn(Optional.of(world));
        JsonNode savedJournal = job.getStateJournal().deepCopy();

        service.retryFailedAnalysisJob(MEMBER_ID, work.getId(), job.getId());

        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.PENDING);
        assertThat(job.getStateJournal()).isEqualTo(savedJournal);
        assertThat(job.getCheckpointStage()).isEqualTo(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
        verify(character, never()).retryFailedOrderedComparison();
        verify(world, never()).retryFailedOrderedComparison();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("자동 격리한 비교 실패는 재개 후에도 유지하고 수동 누적 실패만 다시 비교한다")
    void resumePreservesAutomaticComparisonDecisionsButRetriesManualFailures(boolean automatic) {
        bindRetryLookup();
        ReflectionTestUtils.setField(job, "reviewMode", automatic
                ? org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.AUTOMATIC
                : org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.MANUAL);
        SettingCandidate character = mock(SettingCandidate.class);
        WorldSettingCandidate world = mock(WorldSettingCandidate.class);
        UUID characterId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        when(character.getId()).thenReturn(characterId);
        when(world.getId()).thenReturn(worldId);
        if (automatic) {
            when(character.canDeferFailedComparison()).thenReturn(true);
            when(world.canDeferFailedComparison()).thenReturn(true);
        }
        when(characterRepository.findAllByAnalysisJobIdAndComparisonStatus(job.getId(), CharacterFactComparisonStatus.FAILED))
                .thenReturn(List.of(character));
        when(worldRepository.findAllByAnalysisJobIdAndComparisonStatus(job.getId(), WorldSettingComparisonStatus.FAILED))
                .thenReturn(List.of(world));
        when(characterRepository.findByIdAndWorkIdForUpdate(characterId, work.getId())).thenReturn(Optional.of(character));
        when(worldRepository.findByIdAndWorkIdForUpdate(worldId, work.getId())).thenReturn(Optional.of(world));
        var savedJournal = job.getStateJournal().deepCopy();
        if (automatic) {
            ((com.fasterxml.jackson.databind.node.ArrayNode) savedJournal.path("changes"))
                    .add(objectMapper.createObjectNode().put("eventId", "world-comparison-failed:" + worldId)
                            .set("sourceCandidateIds", objectMapper.createArrayNode().add(worldId.toString())));
            ReflectionTestUtils.setField(job, "stateJournal", savedJournal.deepCopy());
        }

        service.retryFailedAnalysisJob(MEMBER_ID, work.getId(), job.getId());

        assertThat(job.getStateJournal()).isEqualTo(savedJournal);
        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.PENDING);
        if (automatic) {
            verify(character, never()).retryFailedOrderedComparison();
            verify(world, never()).retryFailedOrderedComparison();
        } else {
            verify(character).retryFailedOrderedComparison();
            verify(world).retryFailedOrderedComparison();
        }
    }

    @Test
    @DisplayName("누적 분석의 토큰 중단도 후보별 hidden 작업을 만들지 않고 원래 작업을 재개한다")
    void resumesOrderedQuotaInterruption() {
        job.fail(AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED, "잔액 부족");
        bindRetryLookup();
        assertThat(job.isResumableTokenInterruption()).isFalse();

        service.retryFailedAnalysisJob(MEMBER_ID, work.getId(), job.getId());

        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.PENDING);
        verify(tokenService).ensureAnalysisCanStart(MEMBER_ID);
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("새 분석은 legacy 미검토 후보만 정리하고 이전 누적 실행 원본은 보존한다")
    void newAnalysisPreservesOrderedSourceCandidates() {
        UploadBatch batch = mock(UploadBatch.class);
        UploadFile file = mock(UploadFile.class);
        UUID batchId = UUID.randomUUID();
        when(batch.getId()).thenReturn(batchId);
        when(file.getBatch()).thenReturn(batch);
        when(workRepository.getOwnedWorkForUpdate(work.getId(), MEMBER_ID)).thenReturn(work);
        when(batchRepository.findByIdAndWorkId(batchId, work.getId())).thenReturn(Optional.of(batch));
        when(episodeRepository.findByIdAndWorkIdAndStatusNot(episode.getId(), work.getId(), EpisodeStatus.ARCHIVED))
                .thenReturn(Optional.of(episode));
        when(fileRepository.findById(episode.getSourceFileId())).thenReturn(Optional.of(file));
        when(fileRepository.findAllByBatchIdOrderByCreatedAtAsc(batchId)).thenReturn(List.of(file));
        AnalysisJob legacy = AnalysisJob.create(work, batch, episode, AnalysisJobType.SETTING_EXTRACTION);
        SettingCandidate oldOrderedCharacter = mock(SettingCandidate.class);
        SettingCandidate oldLegacyCharacter = mock(SettingCandidate.class);
        WorldSettingCandidate oldOrderedWorld = mock(WorldSettingCandidate.class);
        WorldSettingCandidate oldLegacyWorld = mock(WorldSettingCandidate.class);
        when(oldOrderedCharacter.getAnalysisJob()).thenReturn(job);
        when(oldOrderedWorld.getAnalysisJob()).thenReturn(job);
        when(oldLegacyCharacter.getAnalysisJob()).thenReturn(legacy);
        when(oldLegacyWorld.getAnalysisJob()).thenReturn(legacy);
        when(characterRepository.findAllSupersededPendingCandidates(work.getId(), batchId, List.of(episode.getId()),
                AnalysisJobType.SETTING_EXTRACTION, SettingCandidateReviewStatus.PENDING_REVIEW))
                .thenReturn(List.of(oldOrderedCharacter, oldLegacyCharacter));
        when(worldRepository.findAllSupersededPendingCandidates(work.getId(), batchId, List.of(episode.getId()),
                AnalysisJobType.SETTING_EXTRACTION, WorldSettingReviewStatus.PENDING_REVIEW))
                .thenReturn(List.of(oldOrderedWorld, oldLegacyWorld));

        service.createAnalysisJobs(MEMBER_ID, work.getId(),
                new AnalysisJobCreateRequest(AnalysisJobType.SETTING_EXTRACTION, batchId, episode.getId()));

        verify(characterRepository).deleteAll(List.of(oldLegacyCharacter));
        verify(worldRepository).deleteAll(List.of(oldLegacyWorld));
        verify(oldOrderedCharacter, never()).getId();
        verify(oldOrderedWorld, never()).getId();
        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(job.getJournalStatus()).isEqualTo(AnalysisJournalStatus.PENDING);
    }

    @Test
    @DisplayName("무효화된 입력은 후보와 토큰 예약에 접근하기 전에 거절한다")
    void rejectsInvalidatedInputBeforeMutation() {
        job.invalidateJournal("사용자 설정 변경");
        bindRetryLookup();
        doThrow(new AppException(AnalysisJobErrorCode.ANALYSIS_RUN_STATE_CONFLICT))
                .when(runStateService).validateResume(job);

        assertThatThrownBy(() -> service.retryFailedAnalysisJob(MEMBER_ID, work.getId(), job.getId()))
                .isInstanceOfSatisfying(AppException.class,
                        error -> assertThat(error.getResultCode()).isEqualTo(AnalysisJobErrorCode.ANALYSIS_RUN_STATE_CONFLICT));

        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(job.getJournalStatus()).isEqualTo(AnalysisJournalStatus.INVALIDATED);
        verifyNoInteractions(characterRepository, worldRepository, tokenService);
    }

    @Test
    @DisplayName("같은 회차의 다른 활성 작업이 있으면 재시도 후보를 변경하지 않는다")
    void rejectsConcurrentEpisodeJob() {
        bindRetryLookup();
        when(jobRepository.existsActiveByEpisodeTarget(episode.getId(),
                Set.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.RUNNING))).thenReturn(true);

        assertThatThrownBy(() -> service.retryFailedAnalysisJob(MEMBER_ID, work.getId(), job.getId()))
                .isInstanceOfSatisfying(AppException.class,
                        error -> assertThat(error.getResultCode()).isEqualTo(AnalysisJobErrorCode.ANALYSIS_JOB_ALREADY_IN_PROGRESS));

        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        verifyNoInteractions(characterRepository, worldRepository, tokenService);
    }

    @Test
    @DisplayName("원문 파기 중이면 누적 작업 재시도를 시작하지 않는다")
    void rejectsSourcePurge() {
        bindRetryLookup();
        when(purgeRepository.existsByEpisodeIdIn(List.of(episode.getId()))).thenReturn(true);

        assertThatThrownBy(() -> service.retryFailedAnalysisJob(MEMBER_ID, work.getId(), job.getId()))
                .isInstanceOf(AppException.class);

        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        verifyNoInteractions(characterRepository, worldRepository, tokenService);
    }

    @Test
    @DisplayName("사용 가능 토큰 검사가 거절되면 후보와 Job 실패 상태를 유지한다")
    void rejectsQuotaBeforeCandidateMutation() {
        bindRetryLookup();
        doThrow(new AppException(org.monitoring.catchholebackend.domain.aitoken.exception.AiTokenErrorCode.AI_TOKEN_QUOTA_EXHAUSTED))
                .when(tokenService).ensureAnalysisCanStart(MEMBER_ID);

        assertThatThrownBy(() -> service.retryFailedAnalysisJob(MEMBER_ID, work.getId(), job.getId()))
                .isInstanceOf(AppException.class);

        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        verifyNoInteractions(characterRepository, worldRepository);
    }

    @Test
    @DisplayName("이미 재개 대기 중인 작업을 다시 요청해도 추가 실행을 만들지 않는다")
    void rejectsDuplicateRetry() {
        job.resumeFailedOrderedAttempt();
        bindRetryLookup();

        assertThatThrownBy(() -> service.retryFailedAnalysisJob(MEMBER_ID, work.getId(), job.getId()))
                .isInstanceOf(AppException.class);

        verifyNoInteractions(runStateService, characterRepository, worldRepository, tokenService);
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("회차 번호나 원문이 달라지면 entity도 같은 입력 재개를 거절한다")
    void entityRejectsChangedSource() {
        episode.updateMetadata(12, episode.getTitle(), episode.getCharCount());

        assertThatThrownBy(job::resumeFailedOrderedAttempt).isInstanceOf(IllegalStateException.class);

        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(job.getSourceEpisodeNo()).isEqualTo(11);
    }

    @Test
    @DisplayName("재claim은 새 lease를 발급해 이전 실행의 늦은 결과를 거절한다")
    void resumeIssuesFreshLease() {
        UUID previous = job.claim(null, null, LocalDateTime.now().plusMinutes(1));
        job.fail("실패");
        job.resumeFailedOrderedAttempt();

        UUID next = job.claim(null, null, LocalDateTime.now().plusMinutes(1));

        assertThat(next).isNotEqualTo(previous);
        assertThat(job.hasLease(previous)).isFalse();
        assertThat(job.getClaimAttemptCount()).isEqualTo(1);
    }

    private void bindRetryLookup() {
        when(workRepository.getOwnedWorkForUpdate(work.getId(), MEMBER_ID)).thenReturn(work);
        when(jobRepository.findByIdAndWorkId(job.getId(), work.getId())).thenReturn(Optional.of(job));
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
    }
}
