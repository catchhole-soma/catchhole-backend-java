package org.monitoring.catchholebackend.global.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.lang.ref.Reference;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.monitoring.catchholebackend.global.monitoring.ServiceUsageSnapshotRepository.Snapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ServiceUsageMetricsTest {
    @Test
    @DisplayName("첫 DB 조회 전에는 서비스 이용 수치를 0 대신 미관측으로 제공한다")
    void exposesUnknownValuesBeforeTheFirstSuccessfulSnapshot() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            ServiceUsageMetrics metrics = new ServiceUsageMetrics(registry);

            assertThat(registry.scrape()).contains(
                    "catchhole_service_members NaN",
                    "catchhole_service_analysis_users_7d NaN",
                    "catchhole_service_analysis_requests_24h NaN",
                    "catchhole_service_analysis_request_episodes_average_24h NaN",
                    "catchhole_service_usage_snapshot_success 0.0",
                    "catchhole_service_usage_snapshot_last_success_timestamp_seconds 0.0",
                    "catchhole_service_usage_requests_24h_complete 0.0");
            assertThat(registry.getMeters()).hasSize(7);
            registry.getMeters().forEach(meter -> assertThat(meter.getId().getTags()).isEmpty());
            Reference.reachabilityFence(metrics);
        } finally { registry.close(); }
    }

    @Test
    @DisplayName("정상적인 빈 집계는 회원과 요청을 0으로 표시하고 평균은 계산하지 않는다")
    void successfulEmptySnapshotDistinguishesZeroCountsFromAnUnknownAverage() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            ServiceUsageMetrics metrics = new ServiceUsageMetrics(registry, new AdjustableClock());

            metrics.updateSnapshot(new Snapshot(0, 0, 0, Double.NaN, true));

            assertThat(registry.scrape()).contains(
                    "catchhole_service_members 0.0",
                    "catchhole_service_analysis_users_7d 0.0",
                    "catchhole_service_analysis_requests_24h 0.0",
                    "catchhole_service_analysis_request_episodes_average_24h NaN",
                    "catchhole_service_usage_snapshot_success 1.0",
                    "catchhole_service_usage_snapshot_last_success_timestamp_seconds 1.7909082E9",
                    "catchhole_service_usage_requests_24h_complete 1.0");
            Reference.reachabilityFence(metrics);
        } finally { registry.close(); }
    }

    @Test
    @DisplayName("집계된 회원과 고유 이용자와 요청당 평균 회차 수를 노출한다")
    void successfulSnapshotExportsTheCalculatedUsageValues() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            ServiceUsageMetrics metrics = new ServiceUsageMetrics(registry, new AdjustableClock());

            metrics.updateSnapshot(new Snapshot(120, 10, 30, 8.5, true));

            assertThat(registry.scrape()).contains(
                    "catchhole_service_members 120.0",
                    "catchhole_service_analysis_users_7d 10.0",
                    "catchhole_service_analysis_requests_24h 30.0",
                    "catchhole_service_analysis_request_episodes_average_24h 8.5",
                    "catchhole_service_usage_snapshot_success 1.0",
                    "catchhole_service_usage_requests_24h_complete 1.0");
            registry.getMeters().forEach(meter -> assertThat(meter.getId().getTags()).isEmpty());
            Reference.reachabilityFence(metrics);
        } finally { registry.close(); }
    }

    @Test
    @DisplayName("과거 요청 묶음을 복원할 수 없으면 부분 집계된 요청 수와 평균을 감춘다")
    void incompleteRequestHistoryDoesNotExposeAnUndercountedRequestTotalOrAverage() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            ServiceUsageMetrics metrics = new ServiceUsageMetrics(registry, new AdjustableClock());
            metrics.updateSnapshot(new Snapshot(119, 8, 29, 7, true));

            metrics.updateSnapshot(new Snapshot(120, 10, 30, 8.5, false));

            assertThat(registry.scrape()).contains(
                    "catchhole_service_members 120.0",
                    "catchhole_service_analysis_users_7d 10.0",
                    "catchhole_service_analysis_requests_24h NaN",
                    "catchhole_service_analysis_request_episodes_average_24h NaN",
                    "catchhole_service_usage_snapshot_success 1.0",
                    "catchhole_service_usage_requests_24h_complete 0.0");
            Reference.reachabilityFence(metrics);
        } finally { registry.close(); }
    }

    @Test
    @DisplayName("조회 실패는 이전 수치와 마지막 성공 시각을 유지하고 복구 후 새 값을 반영한다")
    void failedSnapshotRetainsThePreviousDataAndSuccessfulTimestampUntilRecovery() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            AdjustableClock clock = new AdjustableClock();
            ServiceUsageMetrics metrics = new ServiceUsageMetrics(registry, clock);
            metrics.updateSnapshot(new Snapshot(120, 10, 30, 8.5, true));
            clock.current = clock.current.plusSeconds(60);

            metrics.snapshotFailed();

            assertThat(registry.scrape()).contains(
                    "catchhole_service_members 120.0",
                    "catchhole_service_analysis_users_7d 10.0",
                    "catchhole_service_analysis_requests_24h 30.0",
                    "catchhole_service_analysis_request_episodes_average_24h 8.5",
                    "catchhole_service_usage_snapshot_success 0.0",
                    "catchhole_service_usage_snapshot_last_success_timestamp_seconds 1.7909082E9",
                    "catchhole_service_usage_requests_24h_complete 1.0");

            metrics.updateSnapshot(new Snapshot(121, 11, 31, 8, true));

            assertThat(registry.scrape()).contains(
                    "catchhole_service_members 121.0",
                    "catchhole_service_analysis_users_7d 11.0",
                    "catchhole_service_analysis_requests_24h 31.0",
                    "catchhole_service_analysis_request_episodes_average_24h 8.0",
                    "catchhole_service_usage_snapshot_success 1.0",
                    "catchhole_service_usage_snapshot_last_success_timestamp_seconds 1.79090826E9");
            Reference.reachabilityFence(metrics);
        } finally { registry.close(); }
    }

    @Test
    @DisplayName("메트릭 등록 장애는 서비스 이용 집계 호출로 전파하지 않는다")
    void aRegistryFailureDoesNotEscapeMetricRegistrationOrSnapshotUpdates() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            registry.config().meterFilter(new MeterFilter() {
                @Override public Meter.Id map(Meter.Id id) { throw new IllegalStateException("registry unavailable"); }
            });

            assertThatCode(() -> {
                ServiceUsageMetrics metrics = new ServiceUsageMetrics(registry, new AdjustableClock());
                metrics.updateSnapshot(new Snapshot(120, 10, 30, 8.5, true));
                metrics.snapshotFailed();
            }).doesNotThrowAnyException();
        } finally { registry.close(); }
    }

    private static final class AdjustableClock extends Clock {
        Instant current = Instant.parse("2026-10-02T02:30:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Seoul"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
