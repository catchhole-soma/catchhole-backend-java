package org.monitoring.catchholebackend.domain.analysis.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisLatestCompletedRequestRepository.CompletedRequest;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
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
@DisplayName("PostgreSQL 전체 분석 요청 조회와 안전한 기존 실행 복원")
class AnalysisLatestCompletedRequestPostgresIntegrationTest {
    @Container
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_DB", "latest_request_test")
            .withEnv("POSTGRES_USER", "latest_request_test")
            .withEnv("POSTGRES_PASSWORD", "latest-request-test-only")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
                    .withStartupTimeout(Duration.ofMinutes(1)));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost()
                + ":" + POSTGRES.getMappedPort(5432) + "/latest_request_test");
        properties.add("spring.datasource.username", () -> "latest_request_test");
        properties.add("spring.datasource.password", () -> "latest-request-test-only");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired private EntityManager em;
    @Autowired private AnalysisLatestCompletedRequestRepository repository;

    @Test
    @DisplayName("최초 접수 기록이 온전히 남은 공통 run만 V70에서 복원한다")
    void restoresOnlyUnambiguousFirstAttemptRuns() {
        Member member = Member.register("migration@example.com", "password", "01012345678", "writer");
        em.persist(member);
        Work work = Work.create(member, "migration", WorkGenre.FANTASY, "description");
        em.persist(work);
        LocalDateTime requested = LocalDateTime.of(2026, 10, 2, 10, 0);
        UUID recoverableRun = UUID.randomUUID();
        AnalysisJob first = job(work, 1, recoverableRun, 0, null, requested, requested.plusSeconds(100));
        AnalysisJob second = job(work, 2, recoverableRun, 1, first.getId(), requested.plusSeconds(1), requested.plusSeconds(180));
        AnalysisJob retried = job(work, 3, UUID.randomUUID(), 0, null, requested, requested.plusSeconds(300));
        ReflectionTestUtils.setField(retried, "metricsAttemptNo", 2);
        AnalysisJob independent = job(work, 4, null, 0, null, requested, requested.plusSeconds(400));
        em.flush();

        // Transaction 내에서 새 nullable 필드를 제거해 V69 형태로 만든 뒤 실제 V70 SQL을 적용한다.
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

        assertThat(repository.readLatest()).contains(new CompletedRequest(requested, requested.plusSeconds(180), 2));
        assertThat(em.find(AnalysisJob.class, first.getId()).getMetricsRequestId()).isEqualTo(recoverableRun);
        assertThat(em.find(AnalysisJob.class, second.getId()).getMetricsRequestEpisodeCount()).isEqualTo(2);
        assertThat(em.find(AnalysisJob.class, retried.getId()).getMetricsRequestId()).isNull();
        assertThat(em.find(AnalysisJob.class, independent.getId()).getMetricsRequestId()).isNull();
    }

    private AnalysisJob job(Work work, int number, UUID run, int sequence, UUID predecessor,
            LocalDateTime requested, LocalDateTime ready) {
        Episode episode = Episode.create(work, null, number, "episode", "source/" + number, "v1", "a".repeat(64), 100);
        em.persist(episode);
        AnalysisJob job = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
        if (run != null) {
            job.initializeOrderedRun(run, 1, sequence, predecessor,
                    sequence == 0 ? JsonNodeFactory.instance.objectNode() : null);
            job.prepareOrderedInput("a".repeat(64));
            job.replacePendingJournal(JsonNodeFactory.instance.objectNode()
                    .put("outputStateHash", "b".repeat(64))
                    .set("changes", JsonNodeFactory.instance.arrayNode()));
            job.sealJournal();
        }
        ReflectionTestUtils.setField(job, "attemptRequestedAt", requested);
        job.succeed(null, 0, 0);
        job.recordResultReady(1, ready, "success");
        em.persist(job);
        return job;
    }
}
