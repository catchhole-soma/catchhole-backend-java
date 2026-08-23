package org.monitoring.catchholebackend.domain.episode.processor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.entity.EpisodeSourcePurgeRequest;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodePurgeDataRepository;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeSourcePurgeRequestRepository;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeSourcePurgeStatus;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.global.storage.ObjectStoragePurgeResult;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("회차 원문 파기 처리기 단위 테스트")
class EpisodeSourcePurgeProcessorTest {

    @Mock
    private ObjectStorageService objectStorageService;

    @Mock
    private EpisodePurgeDataRepository purgeDataRepository;

    @Mock
    private EpisodeSourcePurgeRequestRepository purgeRequestRepository;

    @Mock
    private SettingCandidateRepository settingCandidateRepository;

    @Mock
    private WorldSettingCandidateRepository worldSettingCandidateRepository;

    @Mock
    private AnalysisJobRepository analysisJobRepository;

    @Mock
    private UploadFileRepository uploadFileRepository;

    @Mock
    private WorkRepository workRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    @Mock
    private EpisodeSourcePurgeRequest purgeRequest;

    @Mock
    private Episode episode;

    @Mock
    private Work work;

    @Mock
    private UploadFile sourceFile;

    private EpisodeSourcePurgeProcessor processor;
    private UUID workId;
    private UUID episodeId;
    private UUID requestId;
    private UUID sourceFileId;

    @BeforeEach
    void setUp() {
        processor = new EpisodeSourcePurgeProcessor(
                objectStorageService,
                purgeDataRepository,
                purgeRequestRepository,
                settingCandidateRepository,
                worldSettingCandidateRepository,
                analysisJobRepository,
                uploadFileRepository,
                workRepository,
                transactionManager
        );
        workId = UUID.randomUUID();
        episodeId = UUID.randomUUID();
        requestId = UUID.randomUUID();
        sourceFileId = UUID.randomUUID();
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        lenient().when(purgeRequestRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(purgeRequest));
        lenient().when(purgeRequestRepository.findById(requestId)).thenReturn(Optional.of(purgeRequest));
        lenient().when(purgeRequest.getStatus()).thenReturn(EpisodeSourcePurgeStatus.REQUESTED);
        lenient().when(purgeRequest.getWorkId()).thenReturn(workId);
        lenient().when(purgeRequest.getPreviousEpisodeNo()).thenReturn(3);
        lenient().when(purgeRequest.getPreviousContentKey()).thenReturn("works/old-content.txt");
        lenient().when(purgeRequest.getPreviousSourceStorageUrl())
                .thenReturn("s3://upload-batches/old/original.txt");
        lenient().when(purgeRequest.getRetainedContentKey()).thenReturn("works/new-content.txt");
        lenient().when(purgeRequest.getPreviousSourceFileId()).thenReturn(sourceFileId);
        lenient().when(purgeRequest.getEpisode()).thenReturn(episode);
        lenient().when(episode.getId()).thenReturn(episodeId);
        lenient().when(workRepository.findByIdForUpdate(workId)).thenReturn(Optional.of(work));
        lenient().when(uploadFileRepository.findById(sourceFileId)).thenReturn(Optional.of(sourceFile));
    }

    @Test
    @DisplayName("스토리지 파기 후 미확정 후보와 청크를 삭제하고 확정 후보의 근거만 제거한다")
    void purgeRemovesPendingDerivedDataAndScrubsReviewedEvidence() {
        SettingCandidate pendingCharacter = org.mockito.Mockito.mock(SettingCandidate.class);
        SettingCandidate reviewedCharacter = org.mockito.Mockito.mock(SettingCandidate.class);
        WorldSettingCandidate pendingWorld = org.mockito.Mockito.mock(WorldSettingCandidate.class);
        WorldSettingCandidate reviewedWorld = org.mockito.Mockito.mock(WorldSettingCandidate.class);
        AnalysisJob characterComparisonJob = org.mockito.Mockito.mock(AnalysisJob.class);
        AnalysisJob worldComparisonJob = org.mockito.Mockito.mock(AnalysisJob.class);
        UUID characterCandidateId = UUID.randomUUID();
        UUID worldCandidateId = UUID.randomUUID();

        when(objectStorageService.purgeEpisodeSource(
                workId,
                3,
                "works/old-content.txt",
                "s3://upload-batches/old/original.txt",
                "works/new-content.txt"
        )).thenReturn(new ObjectStoragePurgeResult(3, 3, 0));
        when(settingCandidateRepository.findAllByAnalysisTargetEpisodeId(episodeId))
                .thenReturn(List.of(pendingCharacter, reviewedCharacter));
        when(pendingCharacter.isPendingReview()).thenReturn(true);
        when(pendingCharacter.getId()).thenReturn(characterCandidateId);
        when(reviewedCharacter.isPendingReview()).thenReturn(false);
        when(analysisJobRepository.findAllBySettingCandidateIdIn(List.of(characterCandidateId)))
                .thenReturn(List.of(characterComparisonJob));
        when(worldSettingCandidateRepository.findAllBySourceEpisodeId(episodeId))
                .thenReturn(List.of(pendingWorld, reviewedWorld));
        when(pendingWorld.isPendingReview()).thenReturn(true);
        when(pendingWorld.getId()).thenReturn(worldCandidateId);
        when(reviewedWorld.isPendingReview()).thenReturn(false);
        when(analysisJobRepository.findAllByWorldSettingCandidateIdIn(List.of(worldCandidateId)))
                .thenReturn(List.of(worldComparisonJob));

        processor.processRequest(requestId);

        InOrder cleanupOrder = inOrder(workRepository, settingCandidateRepository);
        cleanupOrder.verify(workRepository).findByIdForUpdate(workId);
        cleanupOrder.verify(settingCandidateRepository).findAllByAnalysisTargetEpisodeId(episodeId);
        verify(characterComparisonJob).unlinkSettingCandidate();
        verify(worldComparisonJob).unlinkWorldSettingCandidate();
        verify(settingCandidateRepository).deleteAll(List.of(pendingCharacter));
        verify(worldSettingCandidateRepository).deleteAll(List.of(pendingWorld));
        verify(sourceFile).purgeStoredSource();
        verify(reviewedCharacter).purgeSourceEvidence();
        verify(reviewedWorld).purgeSourceEvidence();
        verify(purgeDataRepository).deleteChunks(episodeId);
        verify(purgeRequestRepository).delete(purgeRequest);
    }

    @Test
    @DisplayName("스토리지 version 파기가 하나라도 실패하면 DB 데이터는 건드리지 않는다")
    void purgeKeepsRequestForRetryAndStopsBeforeDatabaseCleanupWhenStorageIsIncomplete() {
        when(purgeRequest.getRetainedContentKey()).thenReturn(null);
        when(objectStorageService.purgeEpisodeSource(
                workId,
                3,
                "works/old-content.txt",
                "s3://upload-batches/old/original.txt",
                null
        )).thenReturn(new ObjectStoragePurgeResult(3, 2, 1));

        processor.processRequest(requestId);

        verify(settingCandidateRepository, never()).findAllByAnalysisTargetEpisodeId(episodeId);
        verify(worldSettingCandidateRepository, never()).findAllBySourceEpisodeId(episodeId);
        verify(sourceFile, never()).purgeStoredSource();
        verify(purgeDataRepository, never()).deleteChunks(episodeId);
        verify(workRepository, never()).findByIdForUpdate(workId);
        verify(purgeRequest).retry("EPISODE_SOURCE_PURGE_STORAGE_FAILED");
    }

    @Test
    @DisplayName("앞선 요청이 실패해도 같은 배치로 선점한 다음 요청을 계속 처리한다")
    void processPendingRequestsDoesNotStarveLaterRequestsAfterFailure() {
        UUID nextRequestId = UUID.randomUUID();
        UUID nextEpisodeId = UUID.randomUUID();
        EpisodeSourcePurgeRequest nextRequest = org.mockito.Mockito.mock(EpisodeSourcePurgeRequest.class);
        Episode nextEpisode = org.mockito.Mockito.mock(Episode.class);

        when(purgeRequest.getId()).thenReturn(requestId);
        when(nextRequest.getId()).thenReturn(nextRequestId);
        when(nextRequest.getWorkId()).thenReturn(workId);
        when(nextRequest.getPreviousEpisodeNo()).thenReturn(4);
        when(nextRequest.getPreviousContentKey()).thenReturn("works/next-content.txt");
        when(nextRequest.getRetainedContentKey()).thenReturn(null);
        when(nextRequest.getEpisode()).thenReturn(nextEpisode);
        when(nextEpisode.getId()).thenReturn(nextEpisodeId);
        when(purgeRequestRepository.findReadyForUpdate(
                EpisodeSourcePurgeStatus.REQUESTED,
                org.springframework.data.domain.PageRequest.of(0, 10)
        )).thenReturn(List.of(purgeRequest, nextRequest));
        when(purgeRequestRepository.findById(nextRequestId)).thenReturn(Optional.of(nextRequest));
        when(purgeRequestRepository.findByIdForUpdate(nextRequestId)).thenReturn(Optional.of(nextRequest));
        when(objectStorageService.purgeEpisodeSource(
                workId,
                3,
                "works/old-content.txt",
                "s3://upload-batches/old/original.txt",
                "works/new-content.txt"
        )).thenReturn(new ObjectStoragePurgeResult(1, 0, 1));
        when(objectStorageService.purgeEpisodeSource(
                workId,
                4,
                "works/next-content.txt",
                null,
                null
        )).thenReturn(new ObjectStoragePurgeResult(1, 1, 0));

        processor.processPendingRequests();

        verify(purgeRequest).retry("EPISODE_SOURCE_PURGE_STORAGE_FAILED");
        verify(purgeRequestRepository).delete(nextRequest);
    }
}
