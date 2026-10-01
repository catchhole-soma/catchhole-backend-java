package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.monitoring.catchholebackend.domain.analysis.event.AnalysisMetricsEvent;
import org.monitoring.catchholebackend.domain.analysis.event.AnalysisMetricsEvent.Labels;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisMetricsSnapshotRepository.Row;
import org.monitoring.catchholebackend.domain.analysis.type.*;

@DisplayName("분석 지표의 수집 실패와 유휴 상태")
class AnalysisMetricsTest {
    private static final Labels LABELS = new Labels(AnalysisJobType.SETTING_EXTRACTION, AnalysisMode.CONFIRMED_ONLY, AnalysisReviewMode.MANUAL);

    @Test
    @DisplayName("빈 큐는 0이며 조회 실패는 마지막 관측과 진행하는 대기 시간을 보존한다")
    void emptyQueueIsZeroAndSnapshotFailureRetainsPreviousObservationWhileAgeAdvances() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdjustableClock clock = new AdjustableClock();
        AnalysisMetrics metrics = new AnalysisMetrics(registry, clock);
        assertThat(gauge(registry, "catchhole.analysis.oldest.pending")).isZero();
        metrics.updateSnapshot(List.of(new Row(LABELS, "eligible", 2, LocalDateTime.now(clock).minusSeconds(30))));
        assertThat(gauge(registry, "catchhole.analysis.pending.jobs")).isEqualTo(2);
        assertThat(gauge(registry, "catchhole.analysis.oldest.pending")).isEqualTo(30);
        double lastSuccess = registry.get("catchhole.analysis.snapshot.last.success.timestamp").gauge().value();
        clock.current = clock.current.plusSeconds(10);
        metrics.snapshotFailed();
        assertThat(registry.get("catchhole.analysis.snapshot.success").gauge().value()).isZero();
        assertThat(registry.get("catchhole.analysis.snapshot.last.success.timestamp").gauge().value()).isEqualTo(lastSuccess);
        assertThat(gauge(registry, "catchhole.analysis.pending.jobs")).isEqualTo(2);
        assertThat(gauge(registry, "catchhole.analysis.oldest.pending")).isEqualTo(40);
        metrics.updateSnapshot(List.of());
        assertThat(gauge(registry, "catchhole.analysis.pending.jobs")).isZero();
        assertThat(gauge(registry, "catchhole.analysis.oldest.pending")).isZero();
    }

    @Test
    @DisplayName("registry 실패는 커밋 후 지표 콜백 밖으로 전파하지 않는다")
    void registryFailureDoesNotEscapeCommittedMetricCallback() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AnalysisMetrics metrics = new AnalysisMetrics(registry);
        registry.config().meterFilter(new MeterFilter() {
            @Override public Meter.Id map(Meter.Id id) { throw new IllegalStateException("registry unavailable"); }
        });
        assertThatCode(() -> metrics.record(new AnalysisMetricsEvent("accepted", UUID.randomUUID(), 1, LABELS, null, null, null))).doesNotThrowAnyException();
    }

    private double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name).tags("job_type", "setting_extraction", "analysis_mode", "confirmed_only", "review_mode", "manual", "queue_state", "eligible").gauge().value();
    }
    private static final class AdjustableClock extends Clock {
        Instant current = Instant.parse("2026-10-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
