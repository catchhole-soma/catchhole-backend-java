package org.monitoring.catchholebackend.domain.analysis.service;

import io.micrometer.core.instrument.*;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.domain.analysis.event.AnalysisMetricsEvent;
import org.monitoring.catchholebackend.domain.analysis.event.AnalysisMetricsEvent.Labels;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisMetricsSnapshotRepository.Row;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.springframework.stereotype.Component;

/** Micrometer 오류는 분석의 성공 여부를 바꾸지 않는다. DB gauge는 scrape 중 DB를 조회하지 않는다. */
@Slf4j
@Component
public class AnalysisMetrics {
    private final MeterRegistry registry;
    private final Clock clock;
    private final AtomicReference<List<Row>> snapshot = new AtomicReference<>(List.of());
    private volatile double snapshotSuccess;
    private volatile double lastSuccess;

    @org.springframework.beans.factory.annotation.Autowired
    public AnalysisMetrics(MeterRegistry registry) { this(registry, Clock.systemDefaultZone()); }
    AnalysisMetrics(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
        safe(() -> {
            Gauge.builder("catchhole.analysis.snapshot.success", this, m -> m.snapshotSuccess).register(registry);
            Gauge.builder("catchhole.analysis.snapshot.last.success.timestamp", this, m -> m.lastSuccess).baseUnit("seconds").register(registry);
            for (AnalysisJobType type : AnalysisJobType.values()) for (AnalysisMode mode : AnalysisMode.values()) for (AnalysisReviewMode review : AnalysisReviewMode.values()) {
                Labels labels = new Labels(type, mode, review);
                Tags meterTags = tags(labels);
                // 첫 사건 전에 0을 노출해야 Prometheus가 첫 증가량도 관측할 수 있다.
                timer("catchhole.analysis.claim.wait", meterTags);
                for (String action : List.of("requeued", "failed"))
                    registry.counter("catchhole.analysis.recoveries", meterTags.and("action", action));
                if (type == AnalysisJobType.SETTING_EXTRACTION || type == AnalysisJobType.EPISODE_VALIDATION) {
                    registry.counter("catchhole.analysis.accepted", meterTags);
                    registry.counter("catchhole.analysis.retries", meterTags);
                    for (String outcome : List.of("success", "partial_success", "failure", "canceled")) {
                        Tags outcomes = meterTags.and("outcome", outcome);
                        registry.counter("catchhole.analysis.results", outcomes);
                        timer("catchhole.analysis.result.ready", outcomes);
                    }
                }
                for (String queue : List.of("eligible", "dependency_blocked")) {
                    Tags tags = tags(labels).and("queue_state", queue);
                    Gauge.builder("catchhole.analysis.pending.jobs", this, m -> m.count(labels, queue)).tags(tags).register(registry);
                    Gauge.builder("catchhole.analysis.oldest.pending", this, m -> m.age(labels, queue)).tags(tags).baseUnit("seconds").register(registry);
                }
                Gauge.builder("catchhole.analysis.running.jobs", this, m -> m.count(labels, "running")).tags(tags(labels)).register(registry);
            }
        });
    }

    public void record(AnalysisMetricsEvent e) {
        safe(() -> {
            Tags tags = tags(e.labels());
            switch (e.kind()) {
                case "accepted" -> registry.counter("catchhole.analysis.accepted", tags).increment();
                case "retry" -> registry.counter("catchhole.analysis.retries", tags).increment();
                case "recovery" -> registry.counter("catchhole.analysis.recoveries", tags.and("action", e.detail())).increment();
                case "claim" -> timer("catchhole.analysis.claim.wait", tags, e.from(), e.at());
                case "result" -> {
                    Tags outcomes = tags.and("outcome", e.detail());
                    registry.counter("catchhole.analysis.results", outcomes).increment();
                    timer("catchhole.analysis.result.ready", outcomes, e.from(), e.at());
                }
                default -> { }
            }
        });
    }
    private void timer(String name, Tags tags, LocalDateTime from, LocalDateTime at) {
        if (from == null || at == null) return;
        timer(name, tags).record(Duration.between(from, at).isNegative() ? Duration.ZERO : Duration.between(from, at));
    }
    private Timer timer(String name, Tags tags) {
        return Timer.builder(name).tags(tags).publishPercentileHistogram().maximumExpectedValue(Duration.ofHours(6))
                .serviceLevelObjectives(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(15),
                        Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1))
                .register(registry);
    }
    public void updateSnapshot(List<Row> rows) {
        snapshot.set(List.copyOf(rows));
        snapshotSuccess = 1;
        lastSuccess = clock.instant().getEpochSecond();
    }
    public void snapshotFailed() { snapshotSuccess = 0; }
    private double count(Labels labels, String queue) {
        return snapshot.get().stream().filter(r -> r.labels().equals(labels) && r.queueState().equals(queue)).mapToLong(Row::count).sum();
    }
    private double age(Labels labels, String queue) {
        return snapshot.get().stream().filter(r -> r.labels().equals(labels) && r.queueState().equals(queue) && r.oldest() != null)
                .map(Row::oldest).min(LocalDateTime::compareTo)
                .map(oldest -> Math.max(0, Duration.between(oldest, LocalDateTime.now(clock)).toMillis() / 1000.0)).orElse(0.0);
    }
    private Tags tags(Labels labels) {
        return Tags.of("job_type", labels.jobType().name().toLowerCase(Locale.ROOT), "analysis_mode", labels.analysisMode().name().toLowerCase(Locale.ROOT),
                "review_mode", labels.reviewMode().name().toLowerCase(Locale.ROOT));
    }
    static void safe(Runnable action) {
        try { action.run(); } catch (RuntimeException ex) { log.warn("분석 지표 기록에 실패했습니다."); }
    }
}
