package org.monitoring.catchholebackend.domain.analysis.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisLatestCompletedRequestRepository.CompletedRequest;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
@Transactional
@DisplayName("마지막 전체 회차 분석 요청의 준비 시간 조회")
class AnalysisLatestCompletedRequestRepositoryIntegrationTest {
    @Autowired private EntityManager em;
    @Autowired private AnalysisLatestCompletedRequestRepository repository;
    private Work work;
    private UploadBatch batch;
    private final LocalDateTime start = LocalDateTime.of(2026, 10, 2, 10, 0);

    @BeforeEach
    void fixture() {
        Member member = Member.register("latest@example.com", "password", "01012345678", "writer");
        em.persist(member);
        work = Work.create(member, "latest", WorkGenre.FANTASY, "description");
        em.persist(work);
        batch = UploadBatch.create(work, member, UploadType.INITIAL_IMPORT, UploadSourceType.FILE);
        em.persist(batch);
    }

    @Test
    @DisplayName("완료된 요청이 없으면 0초로 추정하지 않는다")
    void noCompletedRequestReturnsEmpty() {
        assertThat(repository.readLatest()).isEmpty();
    }

    @Test
    @DisplayName("모든 회차가 준비된 뒤 최초 접수부터 마지막 회차 준비까지 계산한다")
    void waitsForEveryEpisodeAndUsesLastReadyTime() {
        UUID request = UUID.randomUUID();
        completed(request, episode(1), start, start.plusSeconds(100), 2, "success");
        AnalysisJob second = pending(request, episode(2), start, 2);
        em.flush();
        assertThat(repository.readLatest()).isEmpty();
        second.succeed(null, 0, 0);
        second.recordResultReady(1, start.plusSeconds(180), "partial_success");
        em.flush();
        assertThat(repository.readLatest()).contains(new CompletedRequest(start, start.plusSeconds(180), 2));
    }

    @Test
    @DisplayName("동일 업로드의 서로 다른 요청을 섞지 않고 더 늦게 완료한 짧은 요청을 고른다")
    void separatesRequestsWithinOneBatchAndSelectsByCompletion() {
        Episode episode = episode(1);
        completed(UUID.randomUUID(), episode, start, start.plusSeconds(900), 1, "success");
        completed(UUID.randomUUID(), episode, start.plusSeconds(1000), start.plusSeconds(1050), 1, "success");
        em.flush();
        assertThat(repository.readLatest()).contains(new CompletedRequest(start.plusSeconds(1000), start.plusSeconds(1050), 1));
    }

    @Test
    @DisplayName("독립 재시도는 이전 실패를 대신하며 최초 접수 시각과 전체 회차 수를 유지한다")
    void aNewRetrySupersedesFailedJobWithoutResettingRequestStart() {
        UUID request = UUID.randomUUID();
        Episode firstEpisode = episode(1);
        AnalysisJob failed = pending(request, firstEpisode, start, 2);
        failed.fail("failed");
        completed(request, episode(2), start, start.plusSeconds(100), 2, "success");
        em.flush();
        assertThat(repository.readLatest()).isEmpty();
        AnalysisJob retry = AnalysisJob.create(work, batch, firstEpisode, AnalysisJobType.SETTING_EXTRACTION);
        retry.inheritMetricsRequest(failed);
        retry.succeed(null, 0, 0);
        retry.recordResultReady(1, start.plusSeconds(500), "success");
        em.persist(retry);
        em.flush();
        assertThat(repository.readLatest()).contains(new CompletedRequest(start, start.plusSeconds(500), 2));
    }

    @Test
    @DisplayName("같은 ordered Job의 재시도 대기를 포함한 최초 전체 접수 시간을 보존한다")
    void orderedRetryPreservesOriginalRequestAndIncludesRetryWait() {
        UUID request = UUID.randomUUID();
        AnalysisJob job = AnalysisJob.create(work, batch, episode(1), AnalysisJobType.SETTING_EXTRACTION);
        job.configureMetricsRequest(request, start, 1);
        job.initializeOrderedRun(UUID.randomUUID(), 1, 0, null, JsonNodeFactory.instance.objectNode());
        job.fail("retry");
        em.persist(job);
        em.flush();
        assertThat(repository.readLatest()).isEmpty();
        job.resumeFailedOrderedAttempt();
        job.prepareOrderedInput("a".repeat(64));
        job.replacePendingJournal(JsonNodeFactory.instance.objectNode()
                .put("outputStateHash", "b".repeat(64)).set("changes", JsonNodeFactory.instance.arrayNode()));
        job.sealJournal();
        job.succeed(null, 0, 0);
        job.recordResultReady(2, start.plusSeconds(500), "success");
        em.flush();
        assertThat(job.getMetricsRequestId()).isEqualTo(request);
        assertThat(repository.readLatest()).contains(new CompletedRequest(start, start.plusSeconds(500), 1));
    }

    @Test
    @DisplayName("목표 회차 일부가 삭제돼도 남은 회차만으로 전체 완료를 표시하지 않는다")
    void doesNotMistakeMissingTargetsForCompleteRequest() {
        completed(UUID.randomUUID(), episode(1), start, start.plusSeconds(100), 2, "success");
        em.flush();
        assertThat(repository.readLatest()).isEmpty();
    }

    @Test
    @DisplayName("후속 비교가 미완료거나 취소된 요청과 삭제 중인 작품은 제외한다")
    void excludesRawSuccessWithoutReadyCanceledAndPurgingWork() {
        AnalysisJob raw = pending(UUID.randomUUID(), episode(1), start, 1);
        raw.succeed(null, 0, 0);
        AnalysisJob canceled = pending(UUID.randomUUID(), episode(2), start, 1);
        canceled.cancelForWorkPurge();
        em.flush();
        assertThat(repository.readLatest()).isEmpty();
        completed(UUID.randomUUID(), episode(3), start, start.plusSeconds(100), 1, "success");
        work.startPurging();
        em.flush();
        assertThat(repository.readLatest()).isEmpty();
    }

    @Test
    @DisplayName("자동 누적 분석은 journal과 자동 반영이 끝나야 전체 완료다")
    void requiresSealedJournalAndAutomaticApplicationForOrderedRun() {
        UUID request = UUID.randomUUID();
        AnalysisJob job = AnalysisJob.create(work, batch, episode(1), AnalysisJobType.SETTING_EXTRACTION);
        job.configureMetricsRequest(request, start, 1);
        job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
        job.initializeOrderedRun(UUID.randomUUID(), 1, 0, null, JsonNodeFactory.instance.objectNode());
        job.succeed(null, 0, 0);
        job.recordResultReady(1, start.plusSeconds(100), "success");
        em.persist(job);
        em.flush();
        assertThat(repository.readLatest()).isEmpty();
        ReflectionTestUtils.setField(job, "journalStatus", org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus.SEALED);
        ReflectionTestUtils.setField(job, "automaticAppliedAt", start.plusSeconds(100));
        em.flush();
        assertThat(repository.readLatest()).contains(new CompletedRequest(start, start.plusSeconds(100), 1));
    }

    @Test
    @DisplayName("새 저장 컨텍스트에서도 완료 기록을 읽고 과거 독립 요청은 추정하지 않는다")
    void restoresFromDatabaseAndDoesNotGuessLegacyRequest() {
        AnalysisJob legacy = completed(UUID.randomUUID(), episode(1), start, start.plusSeconds(200), 1, "success");
        ReflectionTestUtils.setField(legacy, "metricsRequestId", null);
        ReflectionTestUtils.setField(legacy, "metricsRequestStartedAt", null);
        ReflectionTestUtils.setField(legacy, "metricsRequestEpisodeCount", null);
        completed(UUID.randomUUID(), episode(2), start, start.plusSeconds(100), 1, "success");
        em.flush();
        em.clear();
        assertThat(repository.readLatest()).contains(new CompletedRequest(start, start.plusSeconds(100), 1));
    }

    private Episode episode(int number) {
        Episode episode = Episode.create(work, null, number, "episode", "source/" + number, "v1", "a".repeat(64), 100);
        em.persist(episode);
        return episode;
    }

    private AnalysisJob pending(UUID request, Episode episode, LocalDateTime requested, int count) {
        AnalysisJob job = AnalysisJob.create(work, batch, episode, AnalysisJobType.SETTING_EXTRACTION);
        job.configureMetricsRequest(request, requested, count);
        em.persist(job);
        return job;
    }

    private AnalysisJob completed(UUID request, Episode episode, LocalDateTime requested, LocalDateTime ready,
            int count, String outcome) {
        AnalysisJob job = pending(request, episode, requested, count);
        job.succeed(null, 0, 0);
        job.recordResultReady(1, ready, outcome);
        return job;
    }
}
