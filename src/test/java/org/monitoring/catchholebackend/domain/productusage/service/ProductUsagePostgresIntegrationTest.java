package org.monitoring.catchholebackend.domain.productusage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.productusage.dto.ProductUsageCompletion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("PostgreSQL 영속 제품 집계의 원자성과 중복 방지")
class ProductUsagePostgresIntegrationTest {
    @Container static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_DB", "product_usage_test").withEnv("POSTGRES_USER", "usage_test")
            .withEnv("POSTGRES_PASSWORD", "test-only-password").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
                    .withStartupTimeout(Duration.ofMinutes(1)));
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/product_usage_test");
        p.add("spring.datasource.username", () -> "usage_test");
        p.add("spring.datasource.password", () -> "test-only-password");
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        p.add("spring.flyway.enabled", () -> "true");
        p.add("spring.docker.compose.enabled", () -> "false");
    }
    @Autowired ProductUsageService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired TransactionTemplate tx;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired org.monitoring.catchholebackend.domain.work.service.WorkService works;
    @Autowired org.monitoring.catchholebackend.domain.work.repository.WorkPurgeDataRepository workPurge;
    final LocalDateTime start = LocalDateTime.of(2026, 11, 11, 0, 0);

    @BeforeEach void initialize() {
        jdbc.execute("truncate product_usage_work_receipts, product_usage_analysis_receipts, product_usage_member_days, product_usage_members, "
                + "product_usage_daily, product_usage_monthly");
        jdbc.update("update product_usage_state set started_at = null, collected_at = null");
        service.initialize(start);
    }

    @Test @DisplayName("전체 migration과 JPA 검증 후 동시 수집이 같은 요청을 한 번만 센다")
    void serializesConcurrentCollectors() {
        assertThat(Integer.parseInt(flyway.info().current().getVersion().getVersion())).isGreaterThanOrEqualTo(124);
        var completion = event();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Runnable collect = () -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            service.accept(completion);
        };
        var first = CompletableFuture.runAsync(collect);
        var second = CompletableFuture.runAsync(collect);
        try { assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { throw new RuntimeException(e); }
        go.countDown();
        CompletableFuture.allOf(first, second).orTimeout(20, java.util.concurrent.TimeUnit.SECONDS).join();
        assertThat(jdbc.queryForObject("select count(*) from product_usage_analysis_receipts", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select completed_requests from product_usage_monthly", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select analysis_members from product_usage_monthly", Long.class)).isEqualTo(1);
    }

    @Test @DisplayName("집계 도중 rollback되면 receipt와 익명 합계가 함께 사라지고 재처리할 수 있다")
    void rollsBackAllAggregateWritesTogether() {
        var completion = event();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            service.accept(completion);
            throw new IllegalStateException("test rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from product_usage_analysis_receipts", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from product_usage_daily", Long.class)).isZero();
        service.accept(completion);
        assertThat(jdbc.queryForObject("select completed_requests from product_usage_monthly", Long.class)).isEqualTo(1);
    }

    private ProductUsageCompletion event() {
        return new ProductUsageCompletion(UUID.randomUUID(), UUID.randomUUID(), 1L,
                start.plusMinutes(1), start.plusMinutes(2), start.plusHours(1));
    }

    @Test @DisplayName("정기 수집과 실제 작품 파기가 경합해도 완료를 한 번 보존하고 식별 행을 남기지 않는다")
    void collectionRacingWithPurgeKeepsOneCompletion() {
        UUID workId = tx.execute(status -> {
            var member = org.monitoring.catchholebackend.domain.member.entity.Member.register(
                    UUID.randomUUID() + "@example.com", "password", "01098765432", "writer");
            em.persist(member);
            UUID id = works.createWork(member.getId(), new org.monitoring.catchholebackend.domain.work.dto.request.WorkCreateRequest(
                    "test", org.monitoring.catchholebackend.domain.work.type.WorkGenre.FANTASY, null)).id();
            var work = em.find(org.monitoring.catchholebackend.domain.work.entity.Work.class, id);
            var episode = org.monitoring.catchholebackend.domain.episode.entity.Episode.create(work, null, 1,
                    "test", "test/source", "v1", "a".repeat(64), 100);
            em.persist(episode);
            var job = org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.create(work, null, episode,
                    org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION);
            job.configureMetricsRequest(UUID.randomUUID(), start.plusMinutes(1), 1);
            job.succeed(null, 0, 0);
            em.persist(job);
            work.startPurging();
            return id;
        });
        var collect = CompletableFuture.runAsync(() -> service.collect(LocalDateTime.now()));
        var purge = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> workPurge.purgeWorkData(workId)));
        CompletableFuture.allOf(collect, purge).orTimeout(20, java.util.concurrent.TimeUnit.SECONDS).join();
        assertThat(jdbc.queryForObject("select completed_requests from product_usage_monthly", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select works_created from product_usage_monthly", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from product_usage_analysis_receipts", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from product_usage_work_receipts", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from works where id = ?", Long.class, workId)).isZero();
    }
}
