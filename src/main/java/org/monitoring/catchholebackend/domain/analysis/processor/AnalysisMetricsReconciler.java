package org.monitoring.catchholebackend.domain.analysis.processor;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisResultReadyTracker;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/** AFTER_COMMIT는 원 transaction 연결을 아직 보유하므로 별도 thread에서 관측한다. */
@Slf4j
@Component
public class AnalysisMetricsReconciler implements DisposableBean {
    private record Attempt(UUID id, int number) {}
    private final ConcurrentHashMap<Attempt, AtomicBoolean> queued = new ConcurrentHashMap<>();
    private final AnalysisResultReadyTracker tracker;
    private final ThreadPoolExecutor executor;

    public AnalysisMetricsReconciler(AnalysisResultReadyTracker tracker) {
        this.tracker = tracker;
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100), runnable -> {
            Thread thread = new Thread(runnable, "analysis-metrics-reconcile");
            thread.setDaemon(true);
            return thread;
        });
    }
    public void submit(UUID id, int attemptNo) {
        Attempt attempt = new Attempt(id, attemptNo);
        AtomicBoolean inserted = new AtomicBoolean();
        AtomicBoolean dirty = queued.compute(attempt, (key, existing) -> {
            if (existing != null) { existing.set(true); return existing; }
            inserted.set(true);
            return new AtomicBoolean();
        });
        if (!inserted.get()) return;
        try {
            executor.execute(() -> {
                boolean repeat;
                do {
                    try { tracker.reconcile(id, attemptNo); }
                    catch (RuntimeException ex) { log.warn("분석 결과 지표 관측에 실패했습니다."); }
                    AtomicBoolean observedDuringRun = new AtomicBoolean();
                    // submit의 dirty 표시와 완료 삭제를 같은 key의 원자적 연산으로 묶는다.
                    queued.compute(attempt, (key, current) -> {
                        if (current != dirty) return current;
                        if (dirty.getAndSet(false)) {
                            observedDuringRun.set(true);
                            return current;
                        }
                        return null;
                    });
                    repeat = observedDuringRun.get();
                } while (repeat);
            });
        } catch (RejectedExecutionException ex) {
            queued.remove(attempt, dirty);
            // DB에 남은 미관측 시도는 15초 scheduler가 다시 요청한다.
            log.warn("분석 결과 지표 관측 대기열이 가득 찼습니다.");
        }
    }
    @Override public void destroy() { executor.shutdownNow(); }
}
