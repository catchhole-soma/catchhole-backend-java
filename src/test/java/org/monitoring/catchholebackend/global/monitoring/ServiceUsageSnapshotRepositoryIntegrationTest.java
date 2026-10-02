package org.monitoring.catchholebackend.global.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.type.MemberStatus;
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
@DisplayName("서비스 이용 현황의 보존된 DB 기록 집계")
class ServiceUsageSnapshotRepositoryIntegrationTest {
    @Autowired private EntityManager em;
    @Autowired private ServiceUsageSnapshotRepository repository;
    private final LocalDateTime asOf = LocalDateTime.of(2026, 10, 2, 12, 0);
    private int nextMember;
    private int nextEpisode;

    @Test
    @DisplayName("이용 정지 계정은 회원으로 세고 탈퇴 중과 탈퇴 계정은 제외한다")
    void countsSuspendedMembersButExcludesWithdrawals() {
        member("active", MemberStatus.ACTIVE);
        member("suspended", MemberStatus.SUSPENDED);
        member("purging", MemberStatus.PURGING);
        member("deleted", MemberStatus.DELETED);
        em.flush();

        var snapshot = repository.read(asOf);
        assertThat(snapshot.members()).isEqualTo(2);
        assertThat(snapshot.analysisUsers7d()).isZero();
        assertThat(snapshot.requests24h()).isZero();
        assertThat(snapshot.averageEpisodes24h()).isNaN();
        assertThat(snapshot.requestHistoryComplete()).isTrue();
    }

    @Test
    @DisplayName("회차 수와 무관하게 요청을 한 번 세고 요청별 대상 수를 평균한다")
    void averagesRequestSizesInsteadOfWeightingEveryJob() {
        Work work = work(member("writer", MemberStatus.ACTIVE));
        request(work, asOf.minusHours(1), 2);
        AnalysisJob failed = request(work, asOf.minusHours(2), 4);
        failed.fail("test failure");
        em.flush();

        var snapshot = repository.read(asOf);
        assertThat(snapshot.analysisUsers7d()).isEqualTo(1);
        assertThat(snapshot.requests24h()).isEqualTo(2);
        assertThat(snapshot.averageEpisodes24h()).isEqualTo(3.0);
        assertThat(snapshot.requestHistoryComplete()).isTrue();
    }

    @Test
    @DisplayName("묶음 정보 없는 과거 요청과 사용자 재시도도 7일 분석 활동에 포함한다")
    void countsLegacyAndRetriedActivityWithoutGuessingRequestGroups() {
        AnalysisJob legacy = request(work(member("legacy", MemberStatus.ACTIVE)), asOf.minusDays(2), 1);
        clearRequest(legacy);
        AnalysisJob retry = request(work(member("retry", MemberStatus.ACTIVE)), asOf.minusDays(8), 1);
        clearRequest(retry);
        retry.markMetricsUserRetry();
        ReflectionTestUtils.setField(retry, "attemptRequestedAt", asOf.minusMinutes(30));
        request(work(member("twice", MemberStatus.ACTIVE)), asOf.minusDays(3), 1);
        request(work(em.find(Member.class, legacy.getWork().getMember().getId())), asOf.minusDays(4), 1);
        AnalysisJob lease = request(work(member("lease", MemberStatus.ACTIVE)), asOf.minusDays(8), 1);
        lease.requeueExpiredLease();
        em.flush();

        var snapshot = repository.read(asOf);
        assertThat(snapshot.analysisUsers7d()).isEqualTo(3);
        assertThat(snapshot.requests24h()).isZero();
        assertThat(snapshot.requestHistoryComplete()).isTrue();
    }

    @Test
    @DisplayName("기간 시작은 포함하고 조회 시각과 그 이후 활동은 제외한다")
    void usesInclusiveLowerAndExclusiveUpperTimeBoundaries() {
        request(work(member("seven-day-edge", MemberStatus.ACTIVE)), asOf.minusDays(7), 1);
        request(work(member("too-old", MemberStatus.ACTIVE)), asOf.minusDays(7).minusNanos(1000), 1);
        request(work(member("day-edge", MemberStatus.ACTIVE)), asOf.minusDays(1), 2);
        request(work(member("before-day", MemberStatus.ACTIVE)), asOf.minusDays(1).minusNanos(1000), 4);
        request(work(member("at-now", MemberStatus.ACTIVE)), asOf, 3);
        request(work(member("future", MemberStatus.ACTIVE)), asOf.plusSeconds(1), 1);
        em.flush();

        var snapshot = repository.read(asOf);
        assertThat(snapshot.analysisUsers7d()).isEqualTo(3);
        assertThat(snapshot.requests24h()).isEqualTo(1);
        assertThat(snapshot.averageEpisodes24h()).isEqualTo(2.0);
        assertThat(snapshot.requestHistoryComplete()).isTrue();
    }

    @Test
    @DisplayName("재시도가 새 Job을 만들거나 같은 ordered Job을 재개해도 최초 요청만 센다")
    void deduplicatesNewRetryJobsAndPreservesRetriedOrderedRequests() {
        Work work = work(member("retry-groups", MemberStatus.ACTIVE));
        AnalysisJob first = request(work, asOf.minusHours(2), 1);
        AnalysisJob retry = job(work, first.getMetricsRequestId(), asOf.minusHours(2), 1,
                AnalysisJobType.SETTING_EXTRACTION, asOf.minusHours(1));
        retry.markMetricsUserRetry();
        AnalysisJob ordered = request(work, asOf.minusHours(3), 1);
        ordered.markMetricsUserRetry();
        ReflectionTestUtils.setField(ordered, "metricsAttemptNo", 2);
        ReflectionTestUtils.setField(ordered, "analysisMode", AnalysisMode.ORDERED_PROVISIONAL);
        AnalysisJob legacyRetry = request(work, asOf.minusMinutes(10), 1);
        legacyRetry.markMetricsUserRetry();
        em.flush();

        var snapshot = repository.read(asOf);
        assertThat(snapshot.requests24h()).isEqualTo(2);
        assertThat(snapshot.averageEpisodes24h()).isEqualTo(1.0);
        assertThat(snapshot.requestHistoryComplete()).isTrue();
    }

    @Test
    @DisplayName("최근 최초 요청의 묶음 정보가 없으면 알려진 요청과 별도로 불완전을 알린다")
    void flagsUnrecoverableRecentOriginalRequests() {
        Work work = work(member("missing-group", MemberStatus.ACTIVE));
        request(work, asOf.minusHours(1), 2);
        clearRequest(request(work, asOf.minusHours(2), 1));
        em.flush();

        var snapshot = repository.read(asOf);
        assertThat(snapshot.requests24h()).isEqualTo(1);
        assertThat(snapshot.averageEpisodes24h()).isEqualTo(2.0);
        assertThat(snapshot.requestHistoryComplete()).isFalse();
    }

    @Test
    @DisplayName("기간 경계를 넘는 같은 묶음의 시각 불일치를 최근 행만 보고 숨기지 않는다")
    void validatesEveryOriginalRowOfARecentCandidateRequest() {
        Work work = work(member("mixed-times", MemberStatus.ACTIVE));
        UUID request = UUID.randomUUID();
        job(work, request, asOf.minusHours(1), 2, AnalysisJobType.SETTING_EXTRACTION, asOf.minusHours(1));
        job(work, request, asOf.minusDays(2), 2, AnalysisJobType.SETTING_EXTRACTION, asOf.minusDays(2));
        em.flush();

        var snapshot = repository.read(asOf);
        assertThat(snapshot.requests24h()).isZero();
        assertThat(snapshot.averageEpisodes24h()).isNaN();
        assertThat(snapshot.requestHistoryComplete()).isFalse();
    }

    @Test
    @DisplayName("같은 요청의 대상 수 불일치와 누락된 대상 회차를 평균에 넣지 않는다")
    void excludesInconsistentOrMissingTargets() {
        Work work = work(member("mixed-sizes", MemberStatus.ACTIVE));
        UUID request = UUID.randomUUID();
        job(work, request, asOf.minusHours(1), 2, AnalysisJobType.SETTING_EXTRACTION, asOf.minusHours(1));
        job(work, request, asOf.minusHours(1), 3, AnalysisJobType.SETTING_EXTRACTION, asOf.minusHours(1));
        job(work, UUID.randomUUID(), asOf.minusHours(2), 2, AnalysisJobType.SETTING_EXTRACTION, asOf.minusHours(2));
        em.flush();

        var snapshot = repository.read(asOf);
        assertThat(snapshot.requests24h()).isZero();
        assertThat(snapshot.requestHistoryComplete()).isFalse();
    }

    @Test
    @DisplayName("최근 저장된 원본의 빈 접수 시각이나 0 대상 수는 불완전 이력이다")
    void flagsNullTimeAndNonPositiveTargetCount() {
        Work work = work(member("invalid-fields", MemberStatus.ACTIVE));
        AnalysisJob missingTime = request(work, asOf.minusHours(1), 1);
        ReflectionTestUtils.setField(missingTime, "metricsRequestStartedAt", null);
        AnalysisJob emptyTargets = request(work, asOf.minusHours(2), 1);
        ReflectionTestUtils.setField(emptyTargets, "metricsRequestEpisodeCount", 0);
        em.flush();

        var snapshot = repository.read(asOf);
        assertThat(snapshot.requests24h()).isZero();
        assertThat(snapshot.requestHistoryComplete()).isFalse();
    }

    @Test
    @DisplayName("계측 전 ordered 첫 재시도로 최초 요청을 알 수 없으면 불완전을 알린다")
    void flagsRecentLegacyFirstOrderedRetryWithUnknownOriginalRequest() {
        Work work = work(member("legacy-ordered", MemberStatus.ACTIVE));
        AnalysisJob retry = request(work, asOf.minusHours(1), 1);
        clearRequest(retry);
        retry.markMetricsUserRetry();
        ReflectionTestUtils.setField(retry, "analysisMode", AnalysisMode.ORDERED_PROVISIONAL);
        em.flush();
        em.createNativeQuery("update analysis_jobs set analysis_mode = 'ORDERED_PROVISIONAL' where id = :id")
                .setParameter("id", retry.getId()).executeUpdate();

        var snapshot = repository.read(asOf);
        assertThat(snapshot.analysisUsers7d()).isEqualTo(1);
        assertThat(snapshot.requests24h()).isZero();
        assertThat(snapshot.requestHistoryComplete()).isFalse();
    }

    @Test
    @DisplayName("계측 전 ordered 첫 재시도의 복원 묶음이 있어도 최초 요청으로 오인하지 않는다")
    void doesNotTrustBackfilledMetadataOfFirstLegacyOrderedRetry() {
        Work work = work(member("legacy-backfill", MemberStatus.ACTIVE));
        AnalysisJob retry = request(work, asOf.minusHours(1), 1);
        retry.markMetricsUserRetry();
        em.flush();
        em.createNativeQuery("update analysis_jobs set analysis_mode = 'ORDERED_PROVISIONAL' where id = :id")
                .setParameter("id", retry.getId()).executeUpdate();

        var snapshot = repository.read(asOf);
        assertThat(snapshot.requests24h()).isZero();
        assertThat(snapshot.requestHistoryComplete()).isFalse();
    }

    @Test
    @DisplayName("계측 전 ordered 두 번째 재시도는 run 복원 UUID로 최초 요청을 증명하지 않는다")
    void doesNotTrustBackfilledLegacyRequestAfterSecondOrderedRetry() {
        Work work = work(member("legacy-second-retry", MemberStatus.ACTIVE));
        AnalysisJob normal = request(work, asOf.minusHours(1), 1);
        normal.markMetricsUserRetry();
        ReflectionTestUtils.setField(normal, "metricsAttemptNo", 2);
        AnalysisJob backfilled = request(work, asOf.minusHours(2), 1);
        backfilled.markMetricsUserRetry();
        ReflectionTestUtils.setField(backfilled, "metricsAttemptNo", 2);
        em.flush();
        em.createNativeQuery("update analysis_jobs set analysis_run_id = :run, "
                        + "analysis_mode = 'ORDERED_PROVISIONAL' where id = :id")
                .setParameter("run", UUID.randomUUID()).setParameter("id", normal.getId()).executeUpdate();
        em.createNativeQuery("update analysis_jobs set analysis_run_id = metrics_request_id, "
                        + "analysis_mode = 'ORDERED_PROVISIONAL' where id = :id")
                .setParameter("id", backfilled.getId()).executeUpdate();

        var snapshot = repository.read(asOf);
        assertThat(snapshot.analysisUsers7d()).isEqualTo(1);
        assertThat(snapshot.requests24h()).isEqualTo(1);
        assertThat(snapshot.averageEpisodes24h()).isEqualTo(1.0);
        assertThat(snapshot.requestHistoryComplete()).isFalse();
    }

    @Test
    @DisplayName("내부 비교와 삭제 중 작품 또는 탈퇴 계정의 분석 기록은 집계하지 않는다")
    void excludesInternalComparisonsAndUnavailableOwnership() {
        Work active = work(member("active-work", MemberStatus.ACTIVE));
        request(active, asOf.minusHours(1), 1);
        AnalysisJob internal = request(active, asOf.minusHours(1), 1);
        ReflectionTestUtils.setField(internal, "jobType", AnalysisJobType.WORLD_SETTING_COMPARISON);
        clearRequest(internal);
        Work purging = work(member("purging-work", MemberStatus.ACTIVE));
        clearRequest(request(purging, asOf.minusHours(1), 1));
        purging.startPurging();
        clearRequest(request(work(member("purging-member", MemberStatus.PURGING)), asOf.minusHours(1), 1));
        clearRequest(request(work(member("deleted-member", MemberStatus.DELETED)), asOf.minusHours(1), 1));
        em.flush();

        var snapshot = repository.read(asOf);
        assertThat(snapshot.members()).isEqualTo(2);
        assertThat(snapshot.analysisUsers7d()).isEqualTo(1);
        assertThat(snapshot.requests24h()).isEqualTo(1);
        assertThat(snapshot.requestHistoryComplete()).isTrue();
    }

    private Member member(String suffix, MemberStatus status) {
        Member member = Member.register(suffix + "@example.com", "test-password", "010%08d".formatted(nextMember++), "writer");
        ReflectionTestUtils.setField(member, "status", status);
        em.persist(member);
        return member;
    }

    private Work work(Member member) {
        Work work = Work.create(member, "service usage", WorkGenre.FANTASY, "description");
        em.persist(work);
        return work;
    }

    private AnalysisJob request(Work work, LocalDateTime requestedAt, int count) {
        UUID request = UUID.randomUUID();
        AnalysisJob first = null;
        for (int i = 0; i < count; i++) {
            AnalysisJob job = job(work, request, requestedAt, count,
                    AnalysisJobType.SETTING_EXTRACTION, requestedAt);
            if (first == null) first = job;
        }
        return first;
    }

    private AnalysisJob job(Work work, UUID request, LocalDateTime requestedAt, int count,
            AnalysisJobType type, LocalDateTime createdAt) {
        Episode episode = Episode.create(work, null, ++nextEpisode, "episode", "source/" + nextEpisode,
                "v1", "a".repeat(64), 100);
        em.persist(episode);
        AnalysisJob job = AnalysisJob.create(work, null, episode, type);
        job.configureMetricsRequest(request, requestedAt, count);
        ReflectionTestUtils.setField(job, "attemptRequestedAt", requestedAt);
        em.persist(job);
        // Auditing이 지정한 생성 시각을 그대로 두면 고정 조회 시각의 기간 fixture가 되지 않는다.
        em.flush();
        em.createNativeQuery("update analysis_jobs set created_at = :created where id = :id")
                .setParameter("created", createdAt).setParameter("id", job.getId()).executeUpdate();
        return job;
    }

    private void clearRequest(AnalysisJob job) {
        ReflectionTestUtils.setField(job, "metricsRequestId", null);
        ReflectionTestUtils.setField(job, "metricsRequestStartedAt", null);
        ReflectionTestUtils.setField(job, "metricsRequestEpisodeCount", null);
    }
}
