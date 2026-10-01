package org.monitoring.catchholebackend.domain.analysis.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.event.AnalysisMetricsEvent.Labels;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisMetricsSnapshotRepository.Row;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
@DisplayName("PostgreSQL 분석 상태 집계")
class AnalysisMetricsSnapshotPostgresIntegrationTest {
    @Container
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_DB", "metrics_test")
            .withEnv("POSTGRES_USER", "metrics_test")
            .withEnv("POSTGRES_PASSWORD", "metrics-test-only")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
                    .withStartupTimeout(Duration.ofMinutes(1)));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost()
                + ":" + POSTGRES.getMappedPort(5432) + "/metrics_test");
        properties.add("spring.datasource.username", () -> "metrics_test");
        properties.add("spring.datasource.password", () -> "metrics-test-only");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired private EntityManager em;
    @Autowired private AnalysisMetricsSnapshotRepository snapshots;

    @Test
    @DisplayName("빈 PostgreSQL DB에서도 대기와 실행 집계 쿼리를 실행한다")
    void readsEmptySnapshot() {
        assertThat(snapshots.read()).isEmpty();
    }

    @Test
    @DisplayName("실행 가능·의존 대기·실행 상태의 개수와 가장 오래된 대기를 집계한다")
    void groupsQueueStatesAndPreservesOldestPendingTime() {
        Member member = Member.register("metrics@example.com", "password", "01012345678", "writer");
        em.persist(member);
        Work work = Work.create(member, "metrics", WorkGenre.FANTASY, "description");
        em.persist(work);
        Episode episode = Episode.create(work, null, 1, "episode", "source/key", "v1", "a".repeat(64), 100);
        em.persist(episode);
        UUID run = UUID.randomUUID();
        LocalDateTime firstPending = LocalDateTime.of(2026, 10, 1, 10, 0);
        LocalDateTime secondPending = firstPending.minusMinutes(5);
        AnalysisJob first = job(work, episode, firstPending);
        first.initializeOrderedRun(run, 1, 0, null, JsonNodeFactory.instance.objectNode());
        em.persist(first);
        AnalysisJob second = job(work, episode, secondPending);
        second.initializeOrderedRun(run, 1, 1, first.getId(), null);
        em.persist(second);
        AnalysisJob third = job(work, episode, secondPending.plusMinutes(1));
        third.initializeOrderedRun(run, 1, 2, second.getId(), null);
        em.persist(third);
        em.persist(job(work, episode, firstPending.minusMinutes(10)));
        em.flush();

        Labels ordered = new Labels(AnalysisJobType.SETTING_EXTRACTION, AnalysisMode.ORDERED_PROVISIONAL, AnalysisReviewMode.MANUAL);
        Labels confirmed = new Labels(AnalysisJobType.SETTING_EXTRACTION, AnalysisMode.CONFIRMED_ONLY, AnalysisReviewMode.MANUAL);
        assertThat(snapshots.read()).containsExactlyInAnyOrder(
                new Row(ordered, "eligible", 1, firstPending),
                new Row(ordered, "dependency_blocked", 2, secondPending),
                new Row(confirmed, "eligible", 1, firstPending.minusMinutes(10)));

        first.claim(null, null, LocalDateTime.now().plusMinutes(5));
        em.flush();
        List<Row> running = snapshots.read();
        assertThat(running).filteredOn(row -> row.queueState().equals("running"))
                .singleElement().satisfies(row -> {
                    assertThat(row.labels()).isEqualTo(ordered);
                    assertThat(row.count()).isEqualTo(1);
                    assertThat(row.oldest()).isNotNull();
                });
        assertThat(running).filteredOn(row -> row.queueState().equals("eligible")).isEmpty();
        assertThat(running).contains(new Row(ordered, "dependency_blocked", 2, secondPending),
                new Row(confirmed, "dependency_blocked", 1, firstPending.minusMinutes(10)));

        first.prepareOrderedInput("a".repeat(64));
        first.replacePendingJournal(JsonNodeFactory.instance.objectNode()
                .put("outputStateHash", "b".repeat(64))
                .set("changes", JsonNodeFactory.instance.arrayNode()));
        first.sealJournal();
        first.succeed(null, 0, 0);
        em.flush();
        assertThat(snapshots.read()).contains(new Row(ordered, "eligible", 1, secondPending));
        work.startPurging();
        em.flush();
        assertThat(snapshots.read()).isEmpty();
    }

    private AnalysisJob job(Work work, Episode episode, LocalDateTime pendingSince) {
        AnalysisJob job = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(job, "pendingSince", pendingSince);
        return job;
    }
}
