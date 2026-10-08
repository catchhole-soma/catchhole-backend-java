package org.monitoring.catchholebackend.global.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenExtensionRequest;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenQuotaExtensionContext;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.type.MemberStatus;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@DisplayName("PostgreSQL 서비스 이용 집계와 V71 기간 인덱스")
class ServiceUsageSnapshotPostgresIntegrationTest {
    @Container
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_DB", "usage_test")
            .withEnv("POSTGRES_USER", "usage_test")
            .withEnv("POSTGRES_PASSWORD", "usage-test-only")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
                    .withStartupTimeout(Duration.ofMinutes(1)));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost()
                + ":" + POSTGRES.getMappedPort(5432) + "/usage_test");
        properties.add("spring.datasource.username", () -> "usage_test");
        properties.add("spring.datasource.password", () -> "usage-test-only");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired private EntityManager em;
    @Autowired private ServiceUsageSnapshotRepository repository;
    @Autowired private Flyway flyway;
    private final LocalDateTime asOf = LocalDateTime.of(2026, 10, 2, 12, 0);
    private int nextEpisode;

    @Test
    @DisplayName("V1부터 V71 적용과 JPA validate 후 실제 요청 집계와 legacy 활동을 읽는다")
    void migratesAndQueriesPreservedActivityAndRequestSizes() {
        Work active = work("active", MemberStatus.ACTIVE);
        request(active, asOf.minusHours(1), 2);
        request(active, asOf.minusHours(2), 6);
        Work suspended = work("suspended", MemberStatus.SUSPENDED);
        AnalysisJob legacy = request(suspended, asOf.minusDays(2), 1);
        clearRequest(legacy);
        Work purging = work("purging", MemberStatus.PURGING);
        clearRequest(request(purging, asOf.minusHours(1), 1));
        em.flush();

        assertThat(Integer.parseInt(flyway.info().current().getVersion().getVersion())).isGreaterThanOrEqualTo(71);
        var snapshot = repository.read(asOf);
        assertThat(snapshot.members()).isEqualTo(2);
        assertThat(snapshot.works()).isEqualTo(2);
        assertThat(snapshot.episodes()).isEqualTo(9);
        assertThat(snapshot.pendingFeedbackRequests()).isZero();
        assertThat(snapshot.analysisUsers7d()).isEqualTo(2);
        assertThat(snapshot.requests24h()).isEqualTo(2);
        assertThat(snapshot.averageEpisodes24h()).isEqualTo(4.0);
        assertThat(snapshot.requestHistoryComplete()).isTrue();

        clearRequest(request(active, asOf.minusHours(3), 1));
        em.flush();
        assertThat(repository.read(asOf).requestHistoryComplete()).isFalse();
    }

    @Test
    @DisplayName("ordered 원본 재개는 한 번 세고 legacy 첫 재시도는 불완전 이력으로 표시한다")
    void distinguishesAnOrderedOriginalFromUnknownLegacyFirstRetry() {
        Work work = work("ordered", MemberStatus.ACTIVE);
        AnalysisJob original = ordered(work, asOf.minusHours(1));
        original.markMetricsUserRetry();
        ReflectionTestUtils.setField(original, "metricsAttemptNo", 2);
        em.flush();

        var snapshot = repository.read(asOf);
        assertThat(snapshot.requests24h()).isEqualTo(1);
        assertThat(snapshot.requestHistoryComplete()).isTrue();

        AnalysisJob unknown = ordered(work, asOf.minusHours(2));
        unknown.markMetricsUserRetry();
        em.flush();
        snapshot = repository.read(asOf);
        assertThat(snapshot.requests24h()).isEqualTo(1);
        assertThat(snapshot.requestHistoryComplete()).isFalse();
    }

    @Test
    @DisplayName("실제 V70 복원 뒤 두 번째 ordered 재시도도 과거 최초 요청으로 추정하지 않는다")
    void doesNotCountSecondRetryOfAV70BackfilledLegacyRequestAsAnOriginal() {
        Work work = work("legacy-second", MemberStatus.ACTIVE);
        AnalysisJob legacy = ordered(work, asOf.minusHours(3));
        UUID jobId = legacy.getId();
        ReflectionTestUtils.setField(legacy, "metricsAttemptNo", 0);
        ReflectionTestUtils.setField(legacy, "attemptRequestedAt", null);
        clearRequest(legacy);
        legacy.fail("test-only initial failure");
        legacy.resumeFailedOrderedAttempt();
        ReflectionTestUtils.setField(legacy, "attemptRequestedAt", asOf.minusHours(2));
        em.flush();

        em.unwrap(Session.class).doWork(connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("drop index idx_analysis_jobs_metrics_request");
                statement.execute("alter table analysis_jobs drop column metrics_request_id, "
                        + "drop column metrics_request_started_at, drop column metrics_request_episode_count");
            }
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V70__track_complete_analysis_requests.sql"))
                    .populate(connection);
        });
        em.clear();
        AnalysisJob restored = em.find(AnalysisJob.class, jobId);
        assertThat(restored.getMetricsRequestId()).isEqualTo(restored.getAnalysisRunId());
        assertThat(restored.getMetricsRequestStartedAt()).isEqualTo(asOf.minusHours(2));
        restored.fail("test-only second failure");
        restored.resumeFailedOrderedAttempt();
        ReflectionTestUtils.setField(restored, "attemptRequestedAt", asOf.minusHours(1));
        em.flush();
        assertThat(restored.getMetricsAttemptNo()).isEqualTo(2);

        var snapshot = repository.read(asOf);
        assertThat(snapshot.analysisUsers7d()).isEqualTo(1);
        assertThat(snapshot.requests24h()).isZero();
        assertThat(snapshot.requestHistoryComplete()).isFalse();
    }

    @Test
    @DisplayName("실제 PostgreSQL에서 보관 회차와 삭제 중 작품과 처리 완료 피드백을 제외한다")
    void countsCurrentContentAndPendingFeedbackOnPostgres() {
        Work active = work("inv-active", MemberStatus.ACTIVE);
        AnalysisJob retained = request(active, asOf.minusDays(8), 1);
        request(active, asOf.minusDays(8), 1).getEpisode().archive();
        Work suspended = work("inv-suspended", MemberStatus.SUSPENDED);
        request(suspended, asOf.minusDays(8), 1);
        Work purging = work("inv-purging-work", MemberStatus.ACTIVE);
        purging.startPurging();
        request(purging, asOf.minusDays(8), 1);
        Work withdrawing = work("inv-withdrawing", MemberStatus.PURGING);
        request(withdrawing, asOf.minusDays(8), 1);
        AiTokenExtensionRequest reward = AiTokenExtensionRequest.requestGeneralFeedbackReward(
                active.getMember(), "검토 대기 일반 피드백");
        em.persist(reward);
        em.persist(AiTokenExtensionRequest.request(suspended.getMember(), "사용량 소진 의견".repeat(5),
                AiTokenQuotaExtensionContext.REQUEST_BLOCKED));
        em.persist(AiTokenExtensionRequest.requestGeneralFeedbackReward(withdrawing.getMember(), "탈퇴 처리 중인 계정의 일반 의견입니다."));
        em.flush();

        var snapshot = repository.read(asOf);
        assertThat(snapshot.works()).isEqualTo(2);
        assertThat(snapshot.episodes()).isEqualTo(2);
        assertThat(snapshot.pendingFeedbackRequests()).isEqualTo(2);

        reward.approve(active.getMember().getId(), 100, asOf);
        retained.getEpisode().archive();
        em.flush();
        snapshot = repository.read(asOf);
        assertThat(snapshot.episodes()).isEqualTo(1);
        assertThat(snapshot.pendingFeedbackRequests()).isEqualTo(1);
    }

    private Work work(String suffix, MemberStatus status) {
        Member member = Member.register(suffix + "@example.com", "test-password", "010" + suffix, "writer");
        ReflectionTestUtils.setField(member, "status", status);
        em.persist(member);
        Work work = Work.create(member, "service usage", WorkGenre.FANTASY, "description");
        em.persist(work);
        return work;
    }

    private AnalysisJob request(Work work, LocalDateTime requestedAt, int count) {
        UUID request = UUID.randomUUID();
        AnalysisJob first = null;
        for (int i = 0; i < count; i++) {
            AnalysisJob job = newJob(work, request, requestedAt, count);
            em.persist(job);
            if (first == null) first = job;
        }
        em.flush();
        em.createNativeQuery("update analysis_jobs set created_at = :created where metrics_request_id = :request")
                .setParameter("created", requestedAt).setParameter("request", request).executeUpdate();
        return first;
    }

    private AnalysisJob ordered(Work work, LocalDateTime requestedAt) {
        UUID request = UUID.randomUUID();
        AnalysisJob job = newJob(work, request, requestedAt, 1);
        job.initializeOrderedRun(UUID.randomUUID(), 1, 0, null, JsonNodeFactory.instance.objectNode());
        em.persist(job);
        em.flush();
        em.createNativeQuery("update analysis_jobs set created_at = :created where id = :id")
                .setParameter("created", requestedAt).setParameter("id", job.getId()).executeUpdate();
        return job;
    }

    private AnalysisJob newJob(Work work, UUID request, LocalDateTime requestedAt, int count) {
        Episode episode = Episode.create(work, null, ++nextEpisode, "episode", "source/" + nextEpisode,
                "v1", "a".repeat(64), 100);
        em.persist(episode);
        AnalysisJob job = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
        job.configureMetricsRequest(request, requestedAt, count);
        ReflectionTestUtils.setField(job, "attemptRequestedAt", requestedAt);
        return job;
    }

    private void clearRequest(AnalysisJob job) {
        ReflectionTestUtils.setField(job, "metricsRequestId", null);
        ReflectionTestUtils.setField(job, "metricsRequestStartedAt", null);
        ReflectionTestUtils.setField(job, "metricsRequestEpisodeCount", null);
    }
}
