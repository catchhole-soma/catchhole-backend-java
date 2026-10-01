package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisMetricsSnapshotRepository;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"spring.config.import=", "analysis.metrics.scheduling-enabled=false", "spring.datasource.hikari.maximum-pool-size=2", "spring.datasource.hikari.connection-timeout=1000"})
@ActiveProfiles("test")
@DisplayName("분석 접수와 결과 준비 지표의 실제 저장 경계")
class AnalysisMetricsIntegrationTest {
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MeterRegistry registry;
    @Autowired AnalysisMetricsSnapshotRepository snapshots;
    @Autowired AnalysisResultReadyTracker tracker;
    @Autowired AnalysisJobWorkerService worker;
    @Autowired AnalysisRunStateService states;
    @Autowired org.monitoring.catchholebackend.domain.analysis.processor.AnalysisMetricsReconciler reconciler;
    private TransactionTemplate tx;

    @BeforeEach void prepare() { tx = new TransactionTemplate(transactions); }

    @Test
    @DisplayName("접수와 종료 결과는 여러 flush에서도 시도별 한 번만 기록하며 롤백을 제외한다")
    void committedReceiptsAndTerminalResultsAreOncePerAttemptAcrossFlushes() {
        double beforeAccepted = count("catchhole.analysis.accepted", null);
        double beforeResults = count("catchhole.analysis.results", "failure");
        UUID id = tx.execute(s -> {
            AnalysisJob job = source(1, false);
            em.flush();
            job.claim(null, null, LocalDateTime.now().plusMinutes(5));
            em.flush();
            job.fail("failed");
            em.flush();
            job.updateCurrentStep("unchanged result");
            em.flush();
            return job.getId();
        });
        assertThat(count("catchhole.analysis.accepted", null) - beforeAccepted).isEqualTo(1);
        assertThat(count("catchhole.analysis.results", "failure") - beforeResults).isEqualTo(1);
        tracker.reconcile(id, 1);
        assertThat(count("catchhole.analysis.results", "failure") - beforeResults).isEqualTo(1);
        tx.executeWithoutResult(s -> {
            source(2, false).fail("rolled back");
            em.flush();
            s.setRollbackOnly();
        });
        assertThat(count("catchhole.analysis.accepted", null) - beforeAccepted).isEqualTo(1);
        assertThat(count("catchhole.analysis.results", "failure") - beforeResults).isEqualTo(1);
    }

    @Test
    @DisplayName("묶음 비교의 모든 원본 회차에 결과를 귀속하고 사람 검토 시간을 제외한다")
    void downstreamGroupsAttributeEverySourceAndExcludeHumanReviewTime() {
        double before = count("catchhole.analysis.results", "partial_success");
        UUID[] ids = tx.execute(s -> {
            AnalysisJob first = source(3, false);
            AnalysisJob second = AnalysisJob.create(first.getWork(), null, first.getEpisode(), AnalysisJobType.SETTING_EXTRACTION);
            em.persist(second);
            SettingCandidate a = candidate(first);
            SettingCandidate b = candidate(second);
            first.claim(null, null, LocalDateTime.now().plusMinutes(5));
            second.claim(null, null, LocalDateTime.now().plusMinutes(5));
            first.succeed(null, 0, 0);
            second.succeed(null, 0, 0);
            return new UUID[]{first.getId(), second.getId(), a.getId(), b.getId()};
        });
        tx.executeWithoutResult(s -> {
            assertThat(em.find(AnalysisJob.class, ids[0]).getResultReadyAt()).isNull();
            assertThat(em.find(AnalysisJob.class, ids[1]).getResultReadyAt()).isNull();
        });
        tx.executeWithoutResult(s -> {
            em.find(SettingCandidate.class, ids[2]).failComparison("comparison failure");
            em.find(SettingCandidate.class, ids[3]).failComparison("comparison failure");
        });
        waitForResult(ids[0]);
        waitForResult(ids[1]);
        assertThat(count("catchhole.analysis.results", "partial_success") - before).isEqualTo(2);
        tx.executeWithoutResult(s -> {
            AnalysisJob source = em.find(AnalysisJob.class, ids[0]);
            assertThat(source.getResultReadyAt()).isEqualTo(em.find(SettingCandidate.class, ids[2]).getComparisonTerminalAt());
        });
        tracker.reconcile(ids[0], 1);
        assertThat(count("catchhole.analysis.results", "partial_success") - before).isEqualTo(2);
    }

    @Test
    @DisplayName("사용자 재시도는 새 접수이며 lease 복구는 같은 접수를 보존한다")
    void orderedRetryStartsFreshReceiptAndLeaseRecoveryPreservesIt() {
        UUID id = tx.execute(s -> {
            AnalysisJob job = source(4, true);
            job.claim(null, null, LocalDateTime.now().plusMinutes(5));
            job.fail("failed");
            return job.getId();
        });
        double accepted = count("catchhole.analysis.accepted", null);
        double retries = count("catchhole.analysis.retries", null);
        tx.executeWithoutResult(s -> {
            AnalysisJob job = em.find(AnalysisJob.class, id);
            job.resumeFailedOrderedAttempt();
            assertThat(job.getMetricsAttemptNo()).isEqualTo(2);
            assertThat(job.getResultReadyAt()).isNull();
        });
        assertThat(count("catchhole.analysis.accepted", null) - accepted).isEqualTo(1);
        assertThat(count("catchhole.analysis.retries", null) - retries).isEqualTo(1);
        tracker.reconcile(id, 1); // late first-attempt callback cannot complete attempt two
        tx.executeWithoutResult(s -> {
            AnalysisJob job = em.find(AnalysisJob.class, id);
            LocalDateTime requested = job.getAttemptRequestedAt();
            job.claim(null, null, LocalDateTime.now().plusMinutes(5));
            em.flush();
            job.requeueExpiredLease();
            assertThat(job.getAttemptRequestedAt()).isEqualTo(requested);
            assertThat(job.getMetricsAttemptNo()).isEqualTo(2);
            assertThat(job.getResultReadyAt()).isNull();
        });
        assertThat(count("catchhole.analysis.accepted", null) - accepted).isEqualTo(1);
    }

    @Test
    @DisplayName("자동 반영 성공에도 비교 실패가 남으면 부분 성공으로 분류한다")
    void automaticSucceededWithFailedComparisonIsPartialSuccess() {
        double before = count("catchhole.analysis.results", "partial_success");
        UUID autoId = tx.execute(s -> {
            AnalysisJob job = source(5, true);
            candidate(job).failComparison("deferred failure");
            job.claim(null, null, LocalDateTime.now().plusMinutes(5));
            job.replacePendingJournal(JsonNodeFactory.instance.objectNode().put("outputStateHash", "b".repeat(64)));
            job.sealJournal();
            job.completeAutomaticApplication();
            job.succeed(null, 0, 0);
            return job.getId();
        });
        waitForResult(autoId);
        assertThat(count("catchhole.analysis.results", "partial_success") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("자동 분석의 보류할 수 없는 비교 실패는 자동 반영 없이 부분 성공 결과를 한 번 기록한다")
    void automaticIncompleteComparisonRecordsResultWithoutAutomaticApplication() {
        double before = count("catchhole.analysis.results", "partial_success");
        long beforeDuration = registry.find("catchhole.analysis.result.ready").tag("outcome", "partial_success")
                .timers().stream().mapToLong(timer -> timer.count()).sum();
        UUID id = tx.execute(status -> {
            AnalysisJob job = source(5, true);
            assertThat(states.prepareInput(job)).isTrue();
            UUID lease = job.claim(null, null, LocalDateTime.now().plusMinutes(5));
            SettingCandidate failed = candidate(job);
            failed.failComparison(AnalysisFailureCode.UNEXPECTED_ERROR, "non-deferable comparison failure");
            assertThat(failed.canDeferFailedComparison()).isFalse();
            job.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED);
            worker.completeAnalysisJob(job.getId(), lease,
                    new org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobCompleteRequest("{}", null, null));
            assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
            assertThat(job.getJournalStatus()).isEqualTo(AnalysisJournalStatus.INCOMPLETE);
            assertThat(job.getAutomaticAppliedAt()).isNull();
            return job.getId();
        });
        tracker.reconcile(id, 1);
        tx.executeWithoutResult(status -> {
            AnalysisJob job = em.find(AnalysisJob.class, id);
            assertThat(job.getResultOutcome()).isEqualTo("partial_success");
            assertThat(job.getResultReadyAt()).isEqualTo(job.getCompletedAt());
        });
        assertThat(count("catchhole.analysis.results", "partial_success") - before).isEqualTo(1);
        assertThat(registry.find("catchhole.analysis.result.ready").tag("outcome", "partial_success")
                .timers().stream().mapToLong(timer -> timer.count()).sum() - beforeDuration).isEqualTo(1);
        tracker.reconcile(id, 1);
        assertThat(count("catchhole.analysis.results", "partial_success") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("봉인된 자동 분석의 성공은 자동 반영 완료를 기다린다")
    void automaticSealedResultStillWaitsForAutomaticApplication() {
        UUID id = tx.execute(status -> {
            AnalysisJob job = source(5, true);
            job.claim(null, null, LocalDateTime.now().plusMinutes(5));
            job.replacePendingJournal(JsonNodeFactory.instance.objectNode().put("outputStateHash", "b".repeat(64)));
            job.sealJournal();
            job.succeed(null, 0, 0);
            return job.getId();
        });
        tracker.reconcile(id, 1);
        tx.executeWithoutResult(status -> {
            AnalysisJob job = em.find(AnalysisJob.class, id);
            assertThat(job.getResultReadyAt()).isNull();
            assertThat(job.getResultOutcome()).isNull();
        });
    }

    @Test
    @DisplayName("대기 집계는 선행 회차와 작품 내 실행 직렬화 조건을 공유한다")
    void pendingSnapshotSharesPredecessorAndWorkSerializationRules() {
        tx.executeWithoutResult(status -> {
            long eligible = queueCount("eligible"), blocked = queueCount("dependency_blocked"), running = queueCount("running");
            AnalysisJob first = source(6, true);
            AnalysisJob second = AnalysisJob.create(first.getWork(), null, first.getEpisode(), AnalysisJobType.SETTING_EXTRACTION);
            second.initializeOrderedRun(first.getAnalysisRunId(), 1, 1, first.getId(), null);
            em.persist(second);
            em.persist(AnalysisJob.create(first.getWork(), null, first.getEpisode(), AnalysisJobType.SETTING_EXTRACTION));
            em.flush();
            assertThat(queueCount("eligible") - eligible).isEqualTo(2);
            assertThat(queueCount("dependency_blocked") - blocked).isEqualTo(1);
            first.claim(null, null, LocalDateTime.now().plusMinutes(5));
            em.flush();
            assertThat(queueCount("running") - running).isEqualTo(1);
            assertThat(queueCount("eligible") - eligible).isZero();
            assertThat(queueCount("dependency_blocked") - blocked).isEqualTo(2);
            first.replacePendingJournal(JsonNodeFactory.instance.objectNode().put("outputStateHash", "b".repeat(64)));
            first.sealJournal();
            first.succeed(null, 0, 0);
            em.flush();
            assertThat(queueCount("eligible") - eligible).isEqualTo(2);
            first.getWork().startPurging();
            em.flush();
            assertThat(queueCount("eligible")).isEqualTo(eligible);
            assertThat(queueCount("dependency_blocked")).isEqualTo(blocked);
            assertThat(queueCount("running")).isEqualTo(running);
            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("자동 선행 회차는 반영 완료와 같은 실행 세대가 필요하다")
    void automaticPredecessorNeedsAppliedBoundaryAndMatchingRunGeneration() {
        tx.executeWithoutResult(status -> {
            long eligible = queueCount("eligible"), blocked = queueCount("dependency_blocked");
            AnalysisJob first = source(5, true);
            AnalysisJob next = AnalysisJob.create(first.getWork(), null, first.getEpisode(), AnalysisJobType.SETTING_EXTRACTION);
            next.initializeOrderedRun(first.getAnalysisRunId(), 1, 1, first.getId(), null);
            em.persist(next);
            AnalysisJob wrongGeneration = AnalysisJob.create(first.getWork(), null, first.getEpisode(), AnalysisJobType.SETTING_EXTRACTION);
            wrongGeneration.initializeOrderedRun(first.getAnalysisRunId(), 2, 1, first.getId(), null);
            em.persist(wrongGeneration);
            first.claim(null, null, LocalDateTime.now().plusMinutes(5));
            first.replacePendingJournal(JsonNodeFactory.instance.objectNode().put("outputStateHash", "b".repeat(64)));
            first.sealJournal();
            first.succeed(null, 0, 0);
            em.flush();
            assertThat(queueCount("eligible") - eligible).isZero();
            assertThat(queueCount("dependency_blocked") - blocked).isEqualTo(2);
            org.springframework.test.util.ReflectionTestUtils.setField(first, "automaticAppliedAt", LocalDateTime.now());
            em.flush();
            assertThat(queueCount("eligible") - eligible).isEqualTo(1);
            assertThat(queueCount("dependency_blocked") - blocked).isEqualTo(1);
            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("빈 추출은 성공이며 미완료 누적 변경 기록은 전체 성공이 아니다")
    void emptyExtractionIsSuccessAndIncompleteOrderedJournalIsNotFullSuccess() {
        UUID[] ids = tx.execute(status -> {
            AnalysisJob good = source(11, false);
            AnalysisJob incomplete = source(12, true);
            good.claim(null, null, LocalDateTime.now().plusMinutes(5));
            incomplete.claim(null, null, LocalDateTime.now().plusMinutes(5));
            incomplete.markJournalIncomplete();
            good.succeed(null, 0, 0);
            incomplete.succeed(null, 0, 0);
            return new UUID[]{good.getId(), incomplete.getId()};
        });
        waitForResult(ids[0]);
        waitForResult(ids[1]);
        tx.executeWithoutResult(status -> {
            assertThat(em.find(AnalysisJob.class, ids[0]).getResultOutcome()).isEqualTo("success");
            assertThat(em.find(AnalysisJob.class, ids[1]).getResultOutcome()).isEqualTo("failure");
        });
    }

    private long queueCount(String state) {
        return snapshots.read().stream().filter(row -> row.queueState().equals(state)).mapToLong(row -> row.count()).sum();
    }

    @Test
    @DisplayName("최종 비교 묶음이 동시에 커밋되어도 회차 결과를 한 번만 기록한다")
    void concurrentFinalGroupsSettleOneSourceOnce() throws Exception {
        UUID[] ids = tx.execute(status -> {
            AnalysisJob job = source(8, false);
            SettingCandidate a = candidate(job);
            SettingCandidate b = candidate(job);
            job.claim(null, null, LocalDateTime.now().plusMinutes(5));
            job.succeed(null, 0, 0);
            return new UUID[]{job.getId(), a.getId(), b.getId()};
        });
        double before = count("catchhole.analysis.results", "partial_success");
        var bothWritten = new java.util.concurrent.CountDownLatch(2);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> finishConcurrentCandidate(ids[1], bothWritten));
            var b = executor.submit(() -> finishConcurrentCandidate(ids[2], bothWritten));
            a.get(20, java.util.concurrent.TimeUnit.SECONDS);
            b.get(20, java.util.concurrent.TimeUnit.SECONDS);
        }
        // 마지막 commit callback 또는 주기적 fallback이 같은 source lock/latch를 공유한다.
        waitForResult(ids[0]);
        assertThat(count("catchhole.analysis.results", "partial_success") - before).isEqualTo(1);
        tx.executeWithoutResult(status -> assertThat(em.find(AnalysisJob.class, ids[0]).getResultReadyAt()).isNotNull());
    }

    @Test
    @DisplayName("같은 커밋에서 row를 삭제해도 취소 결과를 한 번 보존한다")
    void cancellationIsCapturedOnceEvenWhenTheRowIsDeletedInTheSameCommit() {
        UUID id = tx.execute(status -> source(9, false).getId());
        double before = count("catchhole.analysis.results", "canceled");
        tx.executeWithoutResult(status -> {
            AnalysisJob job = em.find(AnalysisJob.class, id);
            job.cancelForWorkPurge();
            job.cancelForWorkPurge();
            em.remove(job);
        });
        assertThat(count("catchhole.analysis.results", "canceled") - before).isEqualTo(1);
        tx.executeWithoutResult(status -> assertThat(em.find(AnalysisJob.class, id)).isNull());
    }

    @Test
    @DisplayName("세계관 자동 비교 종료를 기다리되 사람의 캐릭터 매칭을 기다리지 않는다")
    void worldComparisonsDelayReadinessAndHumanMatchingDoesNot() {
        UUID[] ids = tx.execute(status -> {
            AnalysisJob job = source(10, false);
            SettingCandidate human = SettingCandidate.create(job.getWork(), job.getEpisode(), UUID.randomUUID(), job,
                    SettingEntityType.CHARACTER, "unresolved", "stats.power", "10", SettingValueType.STRING,
                    JsonNodeFactory.instance.textNode("10"), JsonNodeFactory.instance.arrayNode(), new BigDecimal("0.9"), null);
            org.springframework.test.util.ReflectionTestUtils.setField(human, "comparisonStatus", CharacterFactComparisonStatus.WAITING_FOR_CHARACTER_MATCH);
            em.persist(human);
            var world = org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(
                    job.getWork(), job.getEpisode(), job, org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.LOCATION,
                    "place", "rule", "value", JsonNodeFactory.instance.arrayNode(), new BigDecimal("0.9"), null);
            em.persist(world);
            job.claim(null, null, LocalDateTime.now().plusMinutes(5));
            job.succeed(null, 0, 0);
            return new UUID[]{job.getId(), world.getId(), human.getId()};
        });
        tx.executeWithoutResult(status -> assertThat(em.find(AnalysisJob.class, ids[0]).getResultReadyAt()).isNull());
        tx.executeWithoutResult(status -> {
            var candidate = em.find(org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.class, ids[1]);
            candidate.startComparison();
            candidate.failComparison("failed");
        });
        waitForResult(ids[0]);
        tx.executeWithoutResult(status -> {
            AnalysisJob job = em.find(AnalysisJob.class, ids[0]);
            assertThat(job.getResultOutcome()).isEqualTo("partial_success");
            assertThat(job.getResultReadyAt()).isEqualTo(em.find(org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.class, ids[1]).getComparisonTerminalAt());
            assertThat(em.find(SettingCandidate.class, ids[2]).getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
        });
    }

    @Test
    @DisplayName("DB 기본값 후보는 첫 자동 비교 전에 현재 원본 시도를 기록한다")
    void databaseInsertedCandidateCapturesCurrentAttemptBeforeItsFirstComparison() {
        UUID[] ids = tx.execute(status -> {
            AnalysisJob job = source(13, false);
            SettingCandidate candidate = candidate(job);
            em.flush();
            em.createNativeQuery("update setting_candidates set metrics_source_attempt_no = 0 where id = :id")
                    .setParameter("id", candidate.getId()).executeUpdate();
            job.claim(null, null, LocalDateTime.now().plusMinutes(5));
            job.succeed(null, 0, 0);
            return new UUID[]{job.getId(), candidate.getId()};
        });
        tx.executeWithoutResult(status -> {
            SettingCandidate candidate = em.find(SettingCandidate.class, ids[1]);
            assertThat(candidate.getMetricsSourceAttemptNo()).isZero();
            candidate.startComparison();
            candidate.failComparison("failed");
        });
        waitForResult(ids[0]);
        tx.executeWithoutResult(status -> {
            assertThat(em.find(SettingCandidate.class, ids[1]).getMetricsSourceAttemptNo()).isEqualTo(1);
            assertThat(em.find(AnalysisJob.class, ids[0]).getResultOutcome()).isEqualTo("partial_success");
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("DB 기본값의 사람 매칭 대기는 첫 관측 전에 새 비교가 시작되어도 원래 결과 시간을 보존한다")
    void databaseHumanWaitDoesNotBecomeOriginalAutomaticWorkBeforeFirstObservation(boolean directRequest) throws Exception {
        var occupied = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = (java.util.concurrent.ThreadPoolExecutor) org.springframework.test.util.ReflectionTestUtils.getField(reconciler, "executor");
        executor.execute(() -> {
            occupied.countDown();
            try { assertThat(release.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        });
        assertThat(occupied.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        UUID[] ids;
        try {
            ids = tx.execute(status -> {
                AnalysisJob job = source(14, false);
                SettingCandidate candidate = candidate(job);
                em.flush();
                // Python insert는 Java PrePersist를 거치지 않아 초기 사람 대기의 측정 컬럼이 모두 기본값이다.
                em.createNativeQuery("update setting_candidates set comparison_status = 'WAITING_FOR_CHARACTER_MATCH', "
                        + "comparison_terminal_at = null, comparison_terminal_outcome = null, metrics_source_attempt_no = 0, match_status = 'AMBIGUOUS', comparison_reason = 'waiting for human match' where id = :id")
                        .setParameter("id", candidate.getId()).executeUpdate();
                job.claim(null, null, LocalDateTime.now().plusMinutes(5));
                job.succeed(null, 0, 0);
                return new UUID[]{job.getId(), candidate.getId()};
            });
            tx.executeWithoutResult(status -> {
                SettingCandidate candidate = em.find(SettingCandidate.class, ids[1]);
                if (directRequest) candidate.requestComparison();
                else {
                    var character = org.monitoring.catchholebackend.domain.character.entity.WorkCharacter.create(
                            candidate.getWork(), "human choice", null, null, null, null, null, null, null, null, candidate.getEpisode().getId());
                    em.persist(character);
                    candidate.matchExistingCharacter(character);
                    candidate.startComparison();
                }
            });
        } finally { release.countDown(); }
        waitForResult(ids[0]);
        tx.executeWithoutResult(status -> {
            AnalysisJob job = em.find(AnalysisJob.class, ids[0]);
            assertThat(job.getResultOutcome()).isEqualTo("success");
            assertThat(job.getResultReadyAt()).isEqualTo(job.getCompletedAt());
            assertThat(em.find(SettingCandidate.class, ids[1]).getComparisonStatus()).isEqualTo(directRequest
                    ? CharacterFactComparisonStatus.WAITING_FOR_CHARACTER_MATCH : CharacterFactComparisonStatus.PROCESSING);
        });
    }

    private void waitForResult(UUID id) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Boolean ready = tx.execute(status -> em.find(AnalysisJob.class, id).getResultReadyAt() != null);
            if (Boolean.TRUE.equals(ready)) return;
            try { Thread.sleep(10); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        }
        throw new AssertionError("committed source result did not settle");
    }

    private void finishConcurrentCandidate(UUID id, java.util.concurrent.CountDownLatch bothWritten) {
        tx.executeWithoutResult(status -> {
            em.find(SettingCandidate.class, id).failComparison("concurrent failure");
            em.flush();
            bothWritten.countDown();
            try { assertThat(bothWritten.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        });
    }

    private AnalysisJob source(int number, boolean ordered) {
        Member member = Member.register(UUID.randomUUID()+"@example.com", "password", "010" + String.format("%08d", Math.floorMod(UUID.randomUUID().hashCode(), 100000000)), "writer");
        em.persist(member);
        Work work = Work.create(member, "metrics", WorkGenre.FANTASY, "description");
        em.persist(work);
        Episode episode = Episode.create(work, UUID.randomUUID(), number, "episode", "source/key", "v1", "a".repeat(64), 100);
        em.persist(episode);
        AnalysisJob job = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
        if (number == 5) job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
        if (ordered) job.initializeOrderedRun(UUID.randomUUID(), 1, 0, null, JsonNodeFactory.instance.objectNode());
        em.persist(job);
        return job;
    }

    private SettingCandidate candidate(AnalysisJob source) {
        SettingCandidate candidate = SettingCandidate.create(source.getWork(), source.getEpisode(), UUID.randomUUID(), source,
                SettingEntityType.CHARACTER, "hero", "stats.power", "10", SettingValueType.STRING,
                JsonNodeFactory.instance.textNode("10"), JsonNodeFactory.instance.arrayNode(), new BigDecimal("0.9"), null);
        // A matched Fact is automatically runnable; unresolved human matching is excluded from readiness.
        org.springframework.test.util.ReflectionTestUtils.setField(candidate, "comparisonStatus", CharacterFactComparisonStatus.PENDING);
        em.persist(candidate);
        return candidate;
    }

    private double count(String name, String outcome) {
        var search = registry.find(name);
        if (outcome != null) search = search.tag("outcome", outcome);
        return search.counters().stream().mapToDouble(c -> c.count()).sum();
    }
}
