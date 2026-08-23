package org.monitoring.catchholebackend.domain.work.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileRole;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.dto.request.WorkPurgeCreateRequest;
import org.monitoring.catchholebackend.domain.work.dto.response.WorkPurgeResponse;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.entity.WorkPurgeRequest;
import org.monitoring.catchholebackend.domain.work.repository.WorkPurgeRequestRepository;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.work.type.WorkLifecycleStatus;
import org.monitoring.catchholebackend.domain.work.type.WorkPurgeStatus;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.global.storage.ObjectStorage;
import org.monitoring.catchholebackend.global.storage.ObjectStoragePurgeResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "work.purge.worker-drain=0s")
@ActiveProfiles("test")
class WorkPurgeIntegrationTest {

    @Autowired private MemberRepository memberRepository;
    @Autowired private WorkRepository workRepository;
    @Autowired private UploadBatchRepository uploadBatchRepository;
    @Autowired private UploadFileRepository uploadFileRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private AnalysisJobRepository analysisJobRepository;
    @Autowired private WorldSettingRepository worldSettingRepository;
    @Autowired private WorkPurgeRequestRepository purgeRequestRepository;
    @Autowired private WorkPurgeService purgeService;
    @Autowired private WorkPurgeProcessor purgeProcessor;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private ObjectStorage objectStorage;

    private Member member;
    private Work work;
    private UploadBatch batch;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                create table if not exists episode_chunks (
                    id uuid primary key,
                    episode_id uuid not null
                )
                """);
        clearData();
        reset(objectStorage);
        member = memberRepository.save(Member.register(
                "purge-writer@example.com", "encoded-password", "01077778888", "삭제 작가"));
        work = workRepository.save(Work.create(member, "삭제 작품", WorkGenre.FANTASY, null));
        batch = uploadBatchRepository.save(UploadBatch.create(
                work, member, UploadType.SINGLE_EPISODE, UploadSourceType.FILE));
    }

    @AfterEach
    void tearDown() {
        clearData();
    }

    @Test
    void purgeCancelsRunningJobAndDeletesStorageAndDatabaseGraphIdempotently() {
        UploadFile uploadFile = uploadFileRepository.save(UploadFile.create(
                batch,
                UploadFileRole.EPISODE,
                "episode.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "s3://upload-batches/" + batch.getId() + "/episode.txt",
                20
        ));
        Episode episode = episodeRepository.save(Episode.create(
                work,
                uploadFile.getId(),
                1,
                "첫 화",
                "works/" + work.getId() + "/episodes/1/content.txt",
                "v1",
                "hash",
                10
        ));
        AnalysisJob analysisJob = AnalysisJob.create(
                work, batch, episode, AnalysisJobType.SETTING_EXTRACTION);
        analysisJob.claim("test-model", "분석 중", java.time.LocalDateTime.now().plusMinutes(1));
        analysisJob = analysisJobRepository.save(analysisJob);
        worldSettingRepository.save(WorldSetting.create(
                work, WorldSettingCategory.RACE, "인간", "특징", "적응력이 높다"));
        when(objectStorage.purgePrefixes(argThat(prefixes -> prefixes.size() == 2)))
                .thenReturn(new ObjectStoragePurgeResult(3, 3, 0));

        WorkPurgeResponse first = purgeService.requestPurge(
                member.getId(), work.getId(), new WorkPurgeCreateRequest("영구 삭제"));
        WorkPurgeResponse repeated = purgeService.requestPurge(
                member.getId(), work.getId(), new WorkPurgeCreateRequest("영구 삭제"));

        assertThat(repeated.requestId()).isEqualTo(first.requestId());
        assertThat(workRepository.findById(work.getId()).orElseThrow().getLifecycleStatus())
                .isEqualTo(WorkLifecycleStatus.PURGING);
        AnalysisJob canceledJob = analysisJobRepository.findById(analysisJob.getId()).orElseThrow();
        assertThat(canceledJob.getStatus()).isEqualTo(AnalysisJobStatus.CANCELED);
        assertThat(canceledJob.getLeaseToken()).isNull();

        purgeProcessor.processPendingRequests();

        assertThat(workRepository.findById(work.getId())).isEmpty();
        assertThat(uploadBatchRepository.findById(batch.getId())).isEmpty();
        assertThat(episodeRepository.findById(episode.getId())).isEmpty();
        assertThat(analysisJobRepository.findById(analysisJob.getId())).isEmpty();
        assertThat(worldSettingRepository.countByWorkId(work.getId())).isZero();
        WorkPurgeRequest completed = purgeRequestRepository.findById(first.requestId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(WorkPurgeStatus.COMPLETED);
        assertThat(completed.getS3DeletedCount()).isEqualTo(3);
        assertThat(completed.getDbDeletedCount()).isPositive();
        verify(objectStorage).purgePrefixes(argThat(prefixes ->
                prefixes.contains("works/" + work.getId() + "/")
                        && prefixes.contains("upload-batches/" + batch.getId() + "/")));
    }

    @Test
    void storageFailureKeepsDatabaseAndCanBeRetried() {
        when(objectStorage.purgePrefixes(argThat(prefixes -> true)))
                .thenReturn(new ObjectStoragePurgeResult(1, 0, 1));
        WorkPurgeResponse requested = purgeService.requestPurge(
                member.getId(), work.getId(), new WorkPurgeCreateRequest("영구 삭제"));

        purgeProcessor.processPendingRequests();

        WorkPurgeRequest failed = purgeRequestRepository.findById(requested.requestId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(WorkPurgeStatus.FAILED);
        assertThat(failed.isRetryable()).isTrue();
        assertThat(workRepository.findById(work.getId())).isPresent();

        when(objectStorage.purgePrefixes(argThat(prefixes -> true)))
                .thenReturn(new ObjectStoragePurgeResult(1, 1, 0));
        purgeService.retryPurge(member.getId(), requested.requestId());
        purgeProcessor.processPendingRequests();

        assertThat(workRepository.findById(work.getId())).isEmpty();
        assertThat(purgeRequestRepository.findById(requested.requestId()).orElseThrow().getStatus())
                .isEqualTo(WorkPurgeStatus.COMPLETED);
    }

    private void clearData() {
        jdbcTemplate.update("delete from episode_chunks");
        purgeRequestRepository.deleteAll();
        analysisJobRepository.deleteAll();
        episodeRepository.deleteAll();
        uploadFileRepository.deleteAll();
        uploadBatchRepository.deleteAll();
        worldSettingRepository.deleteAll();
        workRepository.deleteAll();
        memberRepository.deleteAll();
    }
}
