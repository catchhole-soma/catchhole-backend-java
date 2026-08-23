package org.monitoring.catchholebackend.domain.work.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.monitoring.catchholebackend.domain.work.entity.WorkPurgeRequest;
import org.springframework.stereotype.Component;

@Component
public class WorkPurgeMetrics {

    private final Counter completedCounter;
    private final Counter failedCounter;
    private final Counter slaBreachedCounter;
    private final Timer completionTimer;
    private final AtomicLong overdueRequests = new AtomicLong();

    public WorkPurgeMetrics(MeterRegistry meterRegistry) {
        completedCounter = meterRegistry.counter("work.purge.completed");
        failedCounter = meterRegistry.counter("work.purge.failed");
        slaBreachedCounter = meterRegistry.counter("work.purge.sla.breached");
        completionTimer = meterRegistry.timer("work.purge.completion");
        Gauge.builder("work.purge.overdue", overdueRequests, AtomicLong::get)
                .description("24시간 안에 완료되지 않은 작품 영구 삭제 요청 수")
                .register(meterRegistry);
    }

    public void recordCompleted(WorkPurgeRequest request) {
        Duration duration = Duration.between(request.getRequestedAt(), request.getCompletedAt());
        completedCounter.increment();
        completionTimer.record(duration);
        if (duration.compareTo(Duration.ofHours(24)) > 0) {
            slaBreachedCounter.increment();
        }
    }

    public void recordFailed() {
        failedCounter.increment();
    }

    public void updateOverdueRequests(long count) {
        overdueRequests.set(count);
    }
}
