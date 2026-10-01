package org.monitoring.catchholebackend.domain.analysis.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisResultReadyTracker;

@DisplayName("분석 결과 지표의 비동기 관측 경합")
class AnalysisMetricsReconcilerTest {
    @Test
    @DisplayName("커밋 전달은 DB 대기를 막지 않으며 겹친 최종 커밋을 재관측한다")
    void commitDispatchReturnsImmediatelyAndCoalescedFinalCommitIsObservedAgain() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), observedAgain = new CountDownLatch(1);
        AtomicInteger observations = new AtomicInteger();
        AnalysisResultReadyTracker blockingStore = new AnalysisResultReadyTracker(null) {
            @Override public void reconcile(UUID id, int attempt) {
                int observation = observations.incrementAndGet();
                if (observation == 1) {
                    started.countDown();
                    try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                } else observedAgain.countDown();
            }
        };
        AnalysisMetricsReconciler reconciler = new AnalysisMetricsReconciler(blockingStore);
        try {
            UUID source = UUID.randomUUID();
            reconciler.submit(source, 1);
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            // submit must finish while the first observation still waits for DB work.
            reconciler.submit(source, 1);
            assertThat(observations.get()).isEqualTo(1);
            release.countDown();
            assertThat(observedAgain.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(observations.get()).isEqualTo(2);
        } finally { release.countDown(); reconciler.destroy(); }
    }
    @Test
    @DisplayName("관측 작업의 종료 순간에 도착한 새 커밋도 최종 상태를 관측한다")
    void finalCommitAtWorkerTeardownIsNeverOrphaned() throws Exception {
        CountDownLatch closing = new CountDownLatch(1), finishClose = new CountDownLatch(1), latestObserved = new CountDownLatch(1);
        AtomicInteger committedVersion = new AtomicInteger(1), observedVersion = new AtomicInteger();
        AnalysisResultReadyTracker store = new AnalysisResultReadyTracker(null) {
            @Override public void reconcile(UUID id, int attempt) {
                int version = committedVersion.get();
                observedVersion.set(version);
                if (version == 2) latestObserved.countDown();
            }
        };
        AnalysisMetricsReconciler reconciler = new AnalysisMetricsReconciler(store);
        // 작업 종료의 map handoff만 잠시 멈춰 실제 마지막 관측과 새 제출 사이의 경계를 재현한다.
        var handoffMap = new java.util.concurrent.ConcurrentHashMap<Object, java.util.concurrent.atomic.AtomicBoolean>() {
            private final java.util.concurrent.atomic.AtomicBoolean paused = new java.util.concurrent.atomic.AtomicBoolean();
            @Override public java.util.concurrent.atomic.AtomicBoolean compute(Object key,
                    java.util.function.BiFunction<? super Object, ? super java.util.concurrent.atomic.AtomicBoolean,
                            ? extends java.util.concurrent.atomic.AtomicBoolean> operation) {
                return super.compute(key, (source, current) -> {
                    var replacement = operation.apply(source, current);
                    if (current != null && replacement == null && paused.compareAndSet(false, true)) {
                        closing.countDown();
                        try { assertThat(finishClose.await(5, TimeUnit.SECONDS)).isTrue(); }
                        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                    }
                    return replacement;
                });
            }
        };
        org.springframework.test.util.ReflectionTestUtils.setField(reconciler, "queued", handoffMap);
        try (var submissions = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            UUID source = UUID.randomUUID();
            reconciler.submit(source, 1);
            assertThat(closing.await(1, TimeUnit.SECONDS)).isTrue();
            committedVersion.set(2);
            var submitted = submissions.submit(() -> reconciler.submit(source, 1));
            finishClose.countDown();
            submitted.get(1, TimeUnit.SECONDS);
            assertThat(latestObserved.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(observedVersion.get()).isEqualTo(2);
        } finally { finishClose.countDown(); reconciler.destroy(); }
    }

}
