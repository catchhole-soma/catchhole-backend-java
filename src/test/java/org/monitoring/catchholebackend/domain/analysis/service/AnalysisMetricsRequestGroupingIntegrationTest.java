package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.monitoring.catchholebackend.domain.analysis.dto.request.AnalysisJobCreateRequest;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
@Transactional
@DisplayName("사용자 분석 요청의 전체 회차 계측 묶음")
class AnalysisMetricsRequestGroupingIntegrationTest {
    @Autowired private EntityManager em;
    @Autowired private AnalysisJobService service;

    @ParameterizedTest
    @EnumSource(value = AnalysisJobType.class, names = {"SETTING_EXTRACTION", "EPISODE_VALIDATION"})
    @DisplayName("run이 없는 수동 다회차 요청도 공통 접수 묶음과 시작 시각을 보존한다")
    void groupsManualMultiEpisodeRequestWithoutAnOrderedRun(AnalysisJobType jobType) {
        Member member = Member.register("request@example.com", "password", "01012345678", "writer");
        em.persist(member);
        Work work = Work.create(member, "request", WorkGenre.FANTASY, "description");
        em.persist(work);
        UploadBatch batch = UploadBatch.create(work, member, UploadType.INITIAL_IMPORT, UploadSourceType.FILE);
        em.persist(batch);
        UploadFile file = UploadFile.create(batch,
                org.monitoring.catchholebackend.domain.upload.type.UploadFileRole.EPISODE,
                "episodes.txt", "text/plain", "source/file", 100L);
        file.markEpisodesParsed(1, 2, 2);
        em.persist(file);
        em.persist(Episode.create(work, file.getId(), 1, "first", "source/1", "v1", "a".repeat(64), 100));
        em.persist(Episode.create(work, file.getId(), 2, "second", "source/2", "v1", "b".repeat(64), 100));
        em.flush();

        var response = service.createAnalysisJobs(member.getId(), work.getId(), new AnalysisJobCreateRequest(
                jobType, batch.getId(), null, AnalysisMode.CONFIRMED_ONLY,
                AnalysisReviewMode.MANUAL));
        List<AnalysisJob> jobs = response.stream().map(row -> em.find(AnalysisJob.class, row.id())).toList();
        assertThat(jobs).hasSize(2);
        assertThat(jobs).allSatisfy(job -> {
            assertThat(job.getAnalysisRunId()).isNull();
            assertThat(job.getMetricsRequestEpisodeCount()).isEqualTo(2);
        });
        assertThat(jobs).extracting(AnalysisJob::getMetricsRequestId).doesNotContainNull()
                .containsOnly(jobs.getFirst().getMetricsRequestId());
        assertThat(jobs).extracting(AnalysisJob::getMetricsRequestStartedAt).doesNotContainNull()
                .containsOnly(jobs.getFirst().getMetricsRequestStartedAt());
        AnalysisJob failed = jobs.getFirst();
        failed.fail("retry");
        jobs.getLast().succeed(null, 0, 0);
        em.flush();
        var retried = service.retryFailedAnalysisJob(member.getId(), work.getId(), failed.getId());
        AnalysisJob retry = em.find(AnalysisJob.class, retried.getFirst().id());
        assertThat(retry.getMetricsRequestId()).isEqualTo(failed.getMetricsRequestId());
        assertThat(retry.getMetricsRequestStartedAt()).isEqualTo(failed.getMetricsRequestStartedAt());
        assertThat(retry.getMetricsRequestEpisodeCount()).isEqualTo(2);
    }
}
