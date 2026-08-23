package org.monitoring.catchholebackend.domain.episode.processor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.exception.EpisodeErrorCode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodePurgeDataRepository;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeSourcePurgeRequestRepository;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.storage.ObjectStoragePurgeResult;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.transaction.PlatformTransactionManager;

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
    private PlatformTransactionManager transactionManager;

    @Mock
    private Episode episode;

    @Mock
    private Work work;

    @Mock
    private UploadFile sourceFile;

    private EpisodeSourcePurgeProcessor processor;
    private UUID workId;
    private UUID episodeId;

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
                transactionManager
        );
        workId = UUID.randomUUID();
        episodeId = UUID.randomUUID();
        when(episode.getWork()).thenReturn(work);
        when(work.getId()).thenReturn(workId);
        lenient().when(episode.getId()).thenReturn(episodeId);
        when(episode.getEpisodeNo()).thenReturn(3);
        when(episode.getContentS3Key()).thenReturn("works/old-content.txt");
        when(sourceFile.getStorageUrl()).thenReturn("s3://upload-batches/old/original.txt");
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

        processor.purgeEpisodeSource(episode, sourceFile, "works/new-content.txt");

        verify(characterComparisonJob).unlinkSettingCandidate();
        verify(worldComparisonJob).unlinkWorldSettingCandidate();
        verify(settingCandidateRepository).deleteAll(List.of(pendingCharacter));
        verify(worldSettingCandidateRepository).deleteAll(List.of(pendingWorld));
        verify(sourceFile).purgeStoredSource();
        verify(reviewedCharacter).purgeSourceEvidence();
        verify(reviewedWorld).purgeSourceEvidence();
        verify(purgeDataRepository).deleteChunks(episodeId);
    }

    @Test
    @DisplayName("스토리지 version 파기가 하나라도 실패하면 DB 데이터는 건드리지 않는다")
    void purgeStopsBeforeDatabaseCleanupWhenStorageIsIncomplete() {
        when(objectStorageService.purgeEpisodeSource(
                workId,
                3,
                "works/old-content.txt",
                "s3://upload-batches/old/original.txt",
                null
        )).thenReturn(new ObjectStoragePurgeResult(3, 2, 1));

        assertThatThrownBy(() -> processor.purgeEpisodeSource(episode, sourceFile, null))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getResultCode())
                                .isEqualTo(EpisodeErrorCode.EPISODE_SOURCE_PURGE_FAILED));

        verify(settingCandidateRepository, never()).findAllByAnalysisTargetEpisodeId(episodeId);
        verify(worldSettingCandidateRepository, never()).findAllBySourceEpisodeId(episodeId);
        verify(sourceFile, never()).purgeStoredSource();
        verify(purgeDataRepository, never()).deleteChunks(episodeId);
    }
}
