package org.monitoring.catchholebackend.domain.relationship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.FailureCode;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipBackfillItemState;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipBackfillWorkState;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@DirtiesContext
@Testcontainers(disabledWithoutDocker = true)
class RelationshipBackfillPostgresIntegrationTest extends RelationshipBackfillIntegrationTest {
    @Container
    static final GenericContainer<?> POSTGRES =
            new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
                    .withEnv("POSTGRES_USER", "gh332")
                    .withEnv("POSTGRES_PASSWORD", "test-only")
                    .withEnv("POSTGRES_DB", "gh332_test")
                    .withExposedPorts(5432)
                    .waitingFor(
                            Wait.forLogMessage(
                                    ".*database system is ready to accept connections.*\\n", 2));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRES.getHost()
                                + ":"
                                + POSTGRES.getMappedPort(5432)
                                + "/gh332_test");
        properties.add("spring.datasource.username", () -> "gh332");
        properties.add("spring.datasource.password", () -> "test-only");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.docker.compose.enabled", () -> "false");
    }

    @Test
    @DisplayName("만료 복구가 작품 잠금을 기다리는 동안 커밋된 heartbeat 임대를 회수하지 않는다")
    void recoveryRefreshesExpiredCandidateAfterConcurrentHeartbeatCommits() throws Exception {
        episode(1);
        coordinator.bootstrapRelationshipBackfill();
        var claimed = claim();
        var originalExpiry = LocalDateTime.now().plusSeconds(5);
        int originalAttempts =
                tx.execute(
                        status -> {
                            var job = em.find(AnalysisJob.class, claimed.analysisJobId());
                            job.renewLease(originalExpiry);
                            return job.getClaimAttemptCount();
                        });
        var heartbeatRenewed = new CountDownLatch(1);
        var commitHeartbeat = new CountDownLatch(1);
        var heartbeatPid = new AtomicInteger();
        var recoveryPid = new AtomicInteger();

        try (var threads = Executors.newFixedThreadPool(2)) {
            try {
                var heartbeat =
                        threads.submit(
                                () ->
                                        tx.execute(
                                                status -> {
                                                    heartbeatPid.set(
                                                            jdbc.queryForObject(
                                                                    "select pg_backend_pid()",
                                                                    Integer.class));
                                                    // The real callback validates the still-live
                                                    // lease and owns both Work and AnalysisJob locks.
                                                    var renewed =
                                                            coordinator.renewRelationshipJobLease(
                                                                    claimed.analysisJobId(),
                                                                    claimed.leaseToken());
                                                    em.flush();
                                                    heartbeatRenewed.countDown();
                                                    awaitLatch(commitHeartbeat);
                                                    return renewed;
                                                }));
                assertThat(heartbeatRenewed.await(10, TimeUnit.SECONDS))
                        .as("heartbeat renews the live lease before its original deadline")
                        .isTrue();

                // Other transactions still see the original deadline until heartbeat commits.
                await().atMost(Duration.ofSeconds(10))
                        .until(() -> LocalDateTime.now().isAfter(originalExpiry));
                var recovery =
                        threads.submit(
                                () ->
                                        tx.execute(
                                                status -> {
                                                    recoveryPid.set(
                                                            jdbc.queryForObject(
                                                                    "select pg_backend_pid()",
                                                                    Integer.class));
                                                    jdbc.execute("set local lock_timeout = '10s'");
                                                    return coordinator.claimRelationshipJob(request);
                                                }));

                // Reaching this lock wait proves recovery already loaded the expired job into
                // its persistence context; a second repository lookup can return that same object.
                await().atMost(Duration.ofSeconds(5))
                        .untilAsserted(
                                () ->
                                        assertThat(
                                                        jdbc.queryForObject(
                                                                """
                                                                select count(*) from pg_stat_activity
                                                                where pid = ?
                                                                  and wait_event_type = 'Lock'
                                                                  and ? = any(pg_blocking_pids(pid))
                                                                """,
                                                                Long.class,
                                                                recoveryPid.get(),
                                                                heartbeatPid.get()))
                                                .isEqualTo(1L));
                assertThat(recoveryPid.get()).isNotEqualTo(heartbeatPid.get());
                commitHeartbeat.countDown();
                var renewed = heartbeat.get(10, TimeUnit.SECONDS);
                assertThat(recovery.get(10, TimeUnit.SECONDS)).isEmpty();

                tx.executeWithoutResult(
                        status -> {
                            var job = em.find(AnalysisJob.class, claimed.analysisJobId());
                            assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.RUNNING);
                            assertThat(job.getLeaseToken()).isEqualTo(claimed.leaseToken());
                            assertThat(job.getClaimAttemptCount()).isEqualTo(originalAttempts);
                            assertThat(job.getLeaseExpiresAt())
                                    .isAfter(originalExpiry)
                                    .isCloseTo(
                                            renewed.leaseExpiresAt(), within(1, ChronoUnit.MICROS));
                        });
                assertThat(items())
                        .singleElement()
                        .satisfies(
                                item -> {
                                    assertThat(item.getState())
                                            .isEqualTo(RelationshipBackfillItemState.RUNNING);
                                    assertThat(item.getJobId()).isEqualTo(claimed.analysisJobId());
                                    assertThat(item.getLeaseToken()).isEqualTo(claimed.leaseToken());
                                });
                assertThat(state().getState()).isEqualTo(RelationshipBackfillWorkState.BUILDING);
                verify(tokens, never())
                        .releaseReservedForAnalysisJob(
                                claimed.analysisJobId(), AiTokenUsageOutcome.WORKER_LEASE_EXPIRED);
            } finally {
                commitHeartbeat.countDown();
            }
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(20, TimeUnit.SECONDS))
                    .as("test releases the heartbeat transaction")
                    .isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while holding the heartbeat transaction", e);
        }
    }

    @Test
    @DisplayName("동시에 도착한 복구 claim은 새 Job과 영구 복구 예산을 정확히 한 번만 만든다")
    void concurrentRecoveryClaimsConsumeOneDurableAttempt() throws Exception {
        episode(1);
        coordinator.bootstrapRelationshipBackfill();
        var original = claim();
        failPreparedComparison(original, FailureCode.COMPARISON_FAILED);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<java.util.Optional<org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobPayload>> call =
                    () -> {
                        ready.countDown();
                        awaitLatch(start);
                        return coordinator.claimRelationshipJob(recoveryRequest(true));
                    };
            var first = threads.submit(call);
            var second = threads.submit(call);
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                var results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
                assertThat(results.stream().filter(java.util.Optional::isPresent)).hasSize(1);
                var recovered = results.stream().flatMap(java.util.Optional::stream).findFirst().orElseThrow();
                assertThat(recovered.analysisJobId()).isNotEqualTo(original.analysisJobId());
                assertThat(relationshipJobCount()).isEqualTo(2);
                assertThat(items().getFirst().getRecoveryAttemptCount()).isEqualTo(1);
                assertThat(items().getFirst().getJobId()).isEqualTo(recovered.analysisJobId());
                finish(recovered);
                assertThat(state().getState()).isEqualTo(RelationshipBackfillWorkState.COMPLETE);
            } finally {
                start.countDown();
            }
        }
    }
}
