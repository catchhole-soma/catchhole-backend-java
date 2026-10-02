package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
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
    @DisplayName("첫 작품 조회 전 소요 시간과 회차 수는 NaN이며 완료·조회 시각은 0이다")
    void lastCompletedRequestExportsUnknownValuesBeforeItsFirstSnapshot() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            AnalysisMetrics metrics = new AnalysisMetrics(registry);
            assertThat(registry.get("catchhole.analysis.last.completed.duration").gauge().value()).isNaN();
            assertThat(registry.get("catchhole.analysis.last.completed.episodes").gauge().value()).isNaN();
            assertThat(registry.get("catchhole.analysis.last.completed.timestamp").gauge().value()).isZero();
            assertThat(registry.get("catchhole.analysis.last.completed.snapshot.success").gauge().value()).isZero();
            assertThat(registry.get("catchhole.analysis.last.completed.snapshot.last.success.timestamp").gauge().value()).isZero();
            for (String name : List.of("duration", "timestamp", "episodes", "snapshot.success", "snapshot.last.success.timestamp"))
                assertThat(registry.get("catchhole.analysis.last.completed." + name).gauge().getId().getTags()).isEmpty();
            assertThat(registry.scrape()).contains(
                    "# TYPE catchhole_analysis_last_completed_duration_seconds gauge",
                    "# TYPE catchhole_analysis_last_completed_timestamp_seconds gauge",
                    "# TYPE catchhole_analysis_last_completed_episodes gauge",
                    "# TYPE catchhole_analysis_last_completed_snapshot_success gauge",
                    "# TYPE catchhole_analysis_last_completed_snapshot_last_success_timestamp_seconds gauge");
            java.lang.ref.Reference.reachabilityFence(metrics);
        } finally { registry.close(); }
    }

    @Test
    @DisplayName("완료된 작품이 없는 정상 조회는 소요 시간을 0으로 만들지 않는다")
    void successfulEmptyLastCompletedSnapshotRetainsUnknownDuration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdjustableClock clock = new AdjustableClock();
        AnalysisMetrics metrics = new AnalysisMetrics(registry, clock);

        metrics.updateLastCompletedRequest(null, null, 0);

        assertThat(lastCompletedGauge(registry, "duration")).isNaN();
        assertThat(lastCompletedGauge(registry, "episodes")).isNaN();
        assertThat(lastCompletedGauge(registry, "timestamp")).isZero();
        assertThat(lastCompletedGauge(registry, "snapshot.success")).isEqualTo(1);
        assertThat(lastCompletedGauge(registry, "snapshot.last.success.timestamp")).isEqualTo(1790812800);
    }

    @Test
    @DisplayName("마지막 작품의 159.877364초와 KST 완료 시각을 정밀도 손실 없이 노출한다")
    void lastCompletedRequestPreservesSubsecondDurationAndUsesTheClockZone() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Clock clock = Clock.fixed(Instant.parse("2026-10-02T02:30:00Z"), ZoneId.of("Asia/Seoul"));
        AnalysisMetrics metrics = new AnalysisMetrics(registry, clock);

        metrics.updateLastCompletedRequest(LocalDateTime.of(2026, 10, 2, 11, 20),
                LocalDateTime.of(2026, 10, 2, 11, 22, 39, 877364000), 3);

        assertThat(lastCompletedGauge(registry, "duration")).isCloseTo(159.877364, within(1e-9));
        assertThat(lastCompletedGauge(registry, "timestamp")).isCloseTo(1790907759.877364, within(1e-6));
        assertThat(lastCompletedGauge(registry, "episodes")).isEqualTo(3);
        assertThat(lastCompletedGauge(registry, "snapshot.success")).isEqualTo(1);
        assertThat(lastCompletedGauge(registry, "snapshot.last.success.timestamp")).isEqualTo(1790908200);
    }

    @Test
    @DisplayName("마지막 작품 조회 실패는 이전 데이터와 성공 시각을 보존하고 복구 후 새 조회를 반영한다")
    void lastCompletedSnapshotFailureRetainsThePreviousObservationUntilRecovery() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdjustableClock clock = new AdjustableClock();
        AnalysisMetrics metrics = new AnalysisMetrics(registry, clock);
        metrics.updateLastCompletedRequest(LocalDateTime.of(2026, 10, 1, 0, 0),
                LocalDateTime.of(2026, 10, 1, 0, 2, 39, 877364000), 2);
        clock.current = clock.current.plusSeconds(15);

        metrics.lastCompletedSnapshotFailed();

        assertThat(lastCompletedGauge(registry, "duration")).isCloseTo(159.877364, within(1e-9));
        assertThat(lastCompletedGauge(registry, "timestamp")).isCloseTo(1790812959.877364, within(1e-6));
        assertThat(lastCompletedGauge(registry, "episodes")).isEqualTo(2);
        assertThat(lastCompletedGauge(registry, "snapshot.success")).isZero();
        assertThat(lastCompletedGauge(registry, "snapshot.last.success.timestamp")).isEqualTo(1790812800);

        metrics.updateLastCompletedRequest(LocalDateTime.of(2026, 10, 1, 0, 3),
                LocalDateTime.of(2026, 10, 1, 0, 4), 1);

        assertThat(lastCompletedGauge(registry, "duration")).isEqualTo(60);
        assertThat(lastCompletedGauge(registry, "timestamp")).isEqualTo(1790813040);
        assertThat(lastCompletedGauge(registry, "episodes")).isEqualTo(1);
        assertThat(lastCompletedGauge(registry, "snapshot.success")).isEqualTo(1);
        assertThat(lastCompletedGauge(registry, "snapshot.last.success.timestamp")).isEqualTo(1790812815);
    }

    @Test
    @DisplayName("새 정상 조회에 완료 작품이 없으면 이전 작품 소요 시간을 지운다")
    void emptyLastCompletedSnapshotClearsAPreviousCompletedRequest() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdjustableClock clock = new AdjustableClock();
        AnalysisMetrics metrics = new AnalysisMetrics(registry, clock);
        metrics.updateLastCompletedRequest(LocalDateTime.of(2026, 10, 1, 0, 0),
                LocalDateTime.of(2026, 10, 1, 0, 1), 1);
        clock.current = clock.current.plusSeconds(30);

        metrics.updateLastCompletedRequest(null, null, 0);

        assertThat(lastCompletedGauge(registry, "duration")).isNaN();
        assertThat(lastCompletedGauge(registry, "episodes")).isNaN();
        assertThat(lastCompletedGauge(registry, "timestamp")).isZero();
        assertThat(lastCompletedGauge(registry, "snapshot.success")).isEqualTo(1);
        assertThat(lastCompletedGauge(registry, "snapshot.last.success.timestamp")).isEqualTo(1790812830);
    }

    @Test
    @DisplayName("첫 접수와 완료 전에 0인 시계열을 수집하고 첫 사건의 증가량을 보존한다")
    void firstEventHasAnExportedZeroBaselineForEveryBoundedLabelCombination() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            AnalysisMetrics metrics = new AnalysisMetrics(registry);
            for (AnalysisJobType type : List.of(AnalysisJobType.SETTING_EXTRACTION, AnalysisJobType.EPISODE_VALIDATION)) {
                for (AnalysisMode mode : AnalysisMode.values()) for (AnalysisReviewMode review : AnalysisReviewMode.values()) {
                    Labels labels = new Labels(type, mode, review);
                    String[] tags = {"job_type", type.name().toLowerCase(java.util.Locale.ROOT),
                            "analysis_mode", mode.name().toLowerCase(java.util.Locale.ROOT),
                            "review_mode", review.name().toLowerCase(java.util.Locale.ROOT)};
                    assertThat(registry.find("catchhole.analysis.accepted").tags(tags).counter()).isNotNull();
                    assertThat(registry.get("catchhole.analysis.accepted").tags(tags).counter().count()).isZero();
                    for (String outcome : List.of("success", "partial_success", "failure", "canceled")) {
                        assertThat(registry.find("catchhole.analysis.results").tags(tags).tag("outcome", outcome).counter()).isNotNull();
                        assertThat(registry.get("catchhole.analysis.results").tags(tags).tag("outcome", outcome).counter().count()).isZero();
                        assertThat(registry.find("catchhole.analysis.result.ready").tags(tags).tag("outcome", outcome).timer()).isNotNull();
                        assertThat(registry.get("catchhole.analysis.result.ready").tags(tags).tag("outcome", outcome).timer().count()).isZero();
                    }
                    String before = registry.scrape();
                    assertThat(before).contains("catchhole_analysis_result_ready_seconds_bucket");
                    LocalDateTime start = LocalDateTime.of(2026, 10, 2, 0, 0);
                    metrics.record(new AnalysisMetricsEvent("accepted", UUID.randomUUID(), 1, labels, null, start, null));
                    metrics.record(new AnalysisMetricsEvent("result", UUID.randomUUID(), 1, labels, start, start.plusSeconds(160), "success"));
                    assertThat(registry.get("catchhole.analysis.accepted").tags(tags).counter().count()).isEqualTo(1);
                    assertThat(registry.get("catchhole.analysis.results").tags(tags).tag("outcome", "success").counter().count()).isEqualTo(1);
                    assertThat(registry.get("catchhole.analysis.result.ready").tags(tags).tag("outcome", "success").timer().count()).isEqualTo(1);
                    assertThat(registry.get("catchhole.analysis.result.ready").tags(tags).tag("outcome", "success").timer()
                            .totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(160);
                }
            }
        } finally { registry.close(); }
    }

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
    @DisplayName("registry 실패는 초기 지표 등록과 커밋 후 콜백 밖으로 전파하지 않는다")
    void registryFailureDoesNotEscapeCommittedMetricCallback() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new MeterFilter() {
            @Override public Meter.Id map(Meter.Id id) { throw new IllegalStateException("registry unavailable"); }
        });
        assertThatCode(() -> new AnalysisMetrics(registry)).doesNotThrowAnyException();
        AnalysisMetrics metrics = new AnalysisMetrics(registry);
        assertThatCode(() -> metrics.record(new AnalysisMetricsEvent("accepted", UUID.randomUUID(), 1, LABELS, null, null, null))).doesNotThrowAnyException();
    }

    private double lastCompletedGauge(SimpleMeterRegistry registry, String name) {
        return registry.get("catchhole.analysis.last.completed." + name).gauge().value();
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
