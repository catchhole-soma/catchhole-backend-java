package org.monitoring.catchholebackend.domain.productusage.repository;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.productusage.service.ProductUsageService;
import org.monitoring.catchholebackend.domain.upload.entity.*;
import org.monitoring.catchholebackend.domain.upload.type.*;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.storage.StoredTextObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test") @Transactional
@DisplayName("제품 지표의 전체 요청 완료 판정")
class ProductUsageSourceIntegrationTest {
    @Autowired EntityManager em;
    @Autowired ProductUsageSourceRepository source;
    @Autowired ProductUsageService service;
    final LocalDateTime start = LocalDateTime.of(2026, 11, 11, 0, 0);
    final LocalDateTime asOf = start.plusDays(1);
    Work work;
    UploadBatch batch;
    int episodeNo;

    @BeforeEach void fixture() {
        Member member = Member.register("usage-source@example.com", "password", "01012345678", "writer");
        em.persist(member);
        work = Work.create(member, "usage", WorkGenre.FANTASY, "test");
        em.persist(work);
        batch = UploadBatch.create(work, member, UploadType.INITIAL_IMPORT, UploadSourceType.FILE);
        em.persist(batch);
        service.initialize(start);
    }

    @Test @DisplayName("모든 회차가 준비되면 부분 성공을 포함해 한 요청으로 수집하고 재발견하지 않는다")
    void collectsOnlyAfterAllTargetsAreReady() {
        UUID request = UUID.randomUUID();
        ready(job(request, 2), start.plusHours(1), "success");
        AnalysisJob second = job(request, 2);
        em.flush();
        assertThat(source.completedRequests(asOf, null)).isEmpty();
        ready(second, start.plusHours(2), "partial_success");
        em.flush();
        var found = source.completedRequests(asOf, null);
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().completedAt()).isEqualTo(start.plusHours(2));
        service.collect(asOf);
        assertThat(source.completedRequests(asOf, null)).isEmpty();
        assertThat(((Number) em.createNativeQuery("select completed_requests from product_usage_monthly")
                .getSingleResult()).longValue()).isEqualTo(1);
    }

    @Test @DisplayName("같은 요청의 새 재시도는 실패한 회차를 대체하고 접수 시각을 유지한다")
    void replacesFailedAttemptWithoutAnotherRequest() {
        UUID request = UUID.randomUUID();
        AnalysisJob failed = job(request, 1);
        failed.fail("test failure");
        em.flush();
        AnalysisJob retry = AnalysisJob.create(work, batch, failed.getEpisode(), AnalysisJobType.SETTING_EXTRACTION);
        retry.inheritMetricsRequest(failed);
        ready(retry, start.plusHours(2), "success");
        em.persist(retry); em.flush();
        assertThat(source.completedRequests(asOf, null)).singleElement().satisfies(row -> {
            assertThat(row.requestId()).isEqualTo(request);
            assertThat(row.requestedAt()).isEqualTo(start.plusMinutes(1));
        });
    }

    @Test @DisplayName("자동 누적 분석은 봉인과 반영이 모두 끝나야 세고 무효화된 원본은 새로 세지 않는다")
    void requiresSealedAndApplied() {
        AnalysisJob job = AnalysisJob.create(work, batch, episode(), AnalysisJobType.SETTING_EXTRACTION);
        job.configureMetricsRequest(UUID.randomUUID(), start.plusMinutes(1), 1);
        job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
        job.initializeOrderedRun(UUID.randomUUID(), 1, 0, null, JsonNodeFactory.instance.objectNode());
        ready(job, start.plusHours(1), "success"); em.persist(job); em.flush();
        assertThat(source.completedRequests(asOf, null)).isEmpty();
        seal(job); em.flush();
        assertThat(source.completedRequests(asOf, null)).isEmpty();
        ReflectionTestUtils.setField(job, "automaticAppliedAt", start.plusHours(1)); em.flush();
        assertThat(source.completedRequests(asOf, null)).hasSize(1);
        job.invalidateJournal("test source change"); em.flush();
        assertThat(source.completedRequests(asOf, null)).isEmpty();
    }

    @Test @DisplayName("대상 누락과 서로 다른 접수 정보는 완료로 추정하지 않는다")
    void rejectsIncompleteAndInconsistentRequests() {
        ready(job(UUID.randomUUID(), 2), start.plusHours(1), "success");
        UUID inconsistent = UUID.randomUUID();
        ready(job(inconsistent, 2), start.plusHours(1), "success");
        AnalysisJob other = job(inconsistent, 2);
        ReflectionTestUtils.setField(other, "metricsRequestStartedAt", start.plusMinutes(2));
        ready(other, start.plusHours(1), "success");
        AnalysisJob failure = job(UUID.randomUUID(), 1);
        ready(failure, start.plusHours(1), "failure");
        em.flush();
        assertThat(source.completedRequests(asOf, null)).isEmpty();
    }

    @Test @DisplayName("회차가 없는 설정집도 독립 요청으로 세며 작품 파기 직전에도 조회할 수 있다")
    void includesSettingBookAndPurgingWork() {
        UploadFile book = UploadFile.create(batch, UploadFileRole.SETTING_BOOK, "book.txt", "text/plain", "test/book", 10);
        em.persist(book);
        SettingBookRevision revision = SettingBookRevision.create(book, 1,
                new StoredTextObject("test/book", "v1", "a".repeat(64), 10));
        em.persist(revision); book.linkCurrentRevision(revision);
        AnalysisJob job = AnalysisJob.createSettingBook(work, book, revision);
        job.initializeOrderedRun(UUID.randomUUID(), 1, 0, null, JsonNodeFactory.instance.objectNode());
        seal(job);
        ReflectionTestUtils.setField(job, "metricsRequestStartedAt", start.plusMinutes(1));
        ReflectionTestUtils.setField(job, "automaticAppliedAt", start.plusHours(1));
        ready(job, start.plusHours(1), "success"); em.persist(job);
        work.startPurging(); em.flush();
        assertThat(source.completedRequests(asOf, work.getId())).singleElement()
                .satisfies(row -> assertThat(row.requestId()).isEqualTo(job.getMetricsRequestId()));
        assertThat(source.completedRequests(asOf, UUID.randomUUID())).isEmpty();
    }

    @Test @DisplayName("오류 검사와 현재 이후의 완료는 제품 사용에 섞지 않는다")
    void excludesValidationAndFutureCompletion() {
        AnalysisJob validation = AnalysisJob.create(work, batch, episode(), AnalysisJobType.EPISODE_VALIDATION);
        ready(validation, start.plusHours(1), "success"); em.persist(validation);
        ready(job(UUID.randomUUID(), 1), asOf, "success"); em.flush();
        assertThat(source.completedRequests(asOf, null)).isEmpty();
    }

    private Episode episode() {
        Episode episode = Episode.create(work, null, ++episodeNo, "test", "test/" + episodeNo, "v1", "a".repeat(64), 100);
        em.persist(episode);
        return episode;
    }
    private AnalysisJob job(UUID request, int count) {
        AnalysisJob job = AnalysisJob.create(work, batch, episode(), AnalysisJobType.SETTING_EXTRACTION);
        job.configureMetricsRequest(request, start.plusMinutes(1), count);
        em.persist(job);
        return job;
    }
    private void ready(AnalysisJob job, LocalDateTime at, String outcome) {
        job.succeed(null, 0, 0);
        job.recordResultReady(1, at, outcome);
    }

    private void seal(AnalysisJob job) {
        job.prepareOrderedInput("a".repeat(64));
        job.replacePendingJournal(JsonNodeFactory.instance.objectNode().put("outputStateHash", "b".repeat(64))
                .set("changes", JsonNodeFactory.instance.arrayNode()));
        job.sealJournal();
    }
}
