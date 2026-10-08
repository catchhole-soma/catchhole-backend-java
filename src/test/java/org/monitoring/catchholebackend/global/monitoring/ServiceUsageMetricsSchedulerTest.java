package org.monitoring.catchholebackend.global.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.lang.ref.Reference;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.global.monitoring.ServiceUsageSnapshotRepository.Snapshot;

class ServiceUsageMetricsSchedulerTest {
    @Test
    @DisplayName("한 번의 집계 기준 시각으로 조회한 모든 서비스 이용 값을 캐시에 반영한다")
    void refreshesAllUsageValuesFromTheCurrentSnapshot() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            AdjustableClock clock = new AdjustableClock();
            ServiceUsageMetrics metrics = new ServiceUsageMetrics(registry, clock);
            ServiceUsageSnapshotRepository repository = mock(ServiceUsageSnapshotRepository.class);
            when(repository.read(LocalDateTime.of(2026, 10, 2, 11, 30)))
                    .thenReturn(new Snapshot(120, 10, 30, 8.5, true, 40, 80, 3));
            ServiceUsageMetricsScheduler scheduler = new ServiceUsageMetricsScheduler(repository, metrics, clock);

            scheduler.refresh();

            assertThat(registry.scrape()).contains(
                    "catchhole_service_members 120.0",
                    "catchhole_service_works 40.0",
                    "catchhole_service_episodes 80.0",
                    "catchhole_service_feedback_pending_requests 3.0",
                    "catchhole_service_analysis_users_7d 10.0",
                    "catchhole_service_analysis_requests_24h 30.0",
                    "catchhole_service_analysis_request_episodes_average_24h 8.5",
                    "catchhole_service_usage_snapshot_success 1.0",
                    "catchhole_service_usage_snapshot_last_success_timestamp_seconds 1.7909082E9");
            Reference.reachabilityFence(metrics);
        } finally { registry.close(); }
    }

    @Test
    @DisplayName("DB 장애는 이전 이용 수치와 성공 시각을 보존하고 다음 정상 조회에서 회복한다")
    void aDatabaseFailureRetainsCachedValuesAndTheNextSuccessfulReadRecovers() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            AdjustableClock clock = new AdjustableClock();
            ServiceUsageMetrics metrics = new ServiceUsageMetrics(registry, clock);
            ServiceUsageSnapshotRepository repository = mock(ServiceUsageSnapshotRepository.class);
            when(repository.read(LocalDateTime.of(2026, 10, 2, 11, 30)))
                    .thenReturn(new Snapshot(120, 10, 30, 8.5, true, 40, 80, 3));
            when(repository.read(LocalDateTime.of(2026, 10, 2, 11, 31)))
                    .thenThrow(new IllegalStateException("database unavailable"));
            when(repository.read(LocalDateTime.of(2026, 10, 2, 11, 32)))
                    .thenReturn(new Snapshot(121, 11, 31, 8, true, 39, 79, 2));
            ServiceUsageMetricsScheduler scheduler = new ServiceUsageMetricsScheduler(repository, metrics, clock);
            scheduler.refresh();
            clock.current = clock.current.plusSeconds(60);

            assertThatCode(scheduler::refresh).doesNotThrowAnyException();

            assertThat(registry.scrape()).contains(
                    "catchhole_service_members 120.0",
                    "catchhole_service_works 40.0",
                    "catchhole_service_episodes 80.0",
                    "catchhole_service_feedback_pending_requests 3.0",
                    "catchhole_service_analysis_users_7d 10.0",
                    "catchhole_service_analysis_requests_24h 30.0",
                    "catchhole_service_analysis_request_episodes_average_24h 8.5",
                    "catchhole_service_usage_snapshot_success 0.0",
                    "catchhole_service_usage_snapshot_last_success_timestamp_seconds 1.7909082E9");
            clock.current = clock.current.plusSeconds(60);

            scheduler.refresh();

            assertThat(registry.scrape()).contains(
                    "catchhole_service_members 121.0",
                    "catchhole_service_works 39.0",
                    "catchhole_service_episodes 79.0",
                    "catchhole_service_feedback_pending_requests 2.0",
                    "catchhole_service_analysis_users_7d 11.0",
                    "catchhole_service_analysis_requests_24h 31.0",
                    "catchhole_service_analysis_request_episodes_average_24h 8.0",
                    "catchhole_service_usage_snapshot_success 1.0",
                    "catchhole_service_usage_snapshot_last_success_timestamp_seconds 1.79090832E9");
            Reference.reachabilityFence(metrics);
        } finally { registry.close(); }
    }

    @Test
    @DisplayName("최초 DB 조회 실패를 이용자와 요청이 없는 정상 집계로 표시하지 않는다")
    void aFailureBeforeTheFirstReadKeepsUsageValuesUnknown() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            AdjustableClock clock = new AdjustableClock();
            ServiceUsageMetrics metrics = new ServiceUsageMetrics(registry, clock);
            ServiceUsageSnapshotRepository repository = mock(ServiceUsageSnapshotRepository.class);
            when(repository.read(LocalDateTime.of(2026, 10, 2, 11, 30)))
                    .thenThrow(new IllegalStateException("database unavailable"));
            ServiceUsageMetricsScheduler scheduler = new ServiceUsageMetricsScheduler(repository, metrics, clock);

            assertThatCode(scheduler::refresh).doesNotThrowAnyException();

            assertThat(registry.scrape()).contains(
                    "catchhole_service_members NaN",
                    "catchhole_service_works NaN",
                    "catchhole_service_episodes NaN",
                    "catchhole_service_feedback_pending_requests NaN",
                    "catchhole_service_analysis_users_7d NaN",
                    "catchhole_service_analysis_requests_24h NaN",
                    "catchhole_service_analysis_request_episodes_average_24h NaN",
                    "catchhole_service_usage_snapshot_success 0.0",
                    "catchhole_service_usage_snapshot_last_success_timestamp_seconds 0.0");
            Reference.reachabilityFence(metrics);
        } finally { registry.close(); }
    }

    private static final class AdjustableClock extends Clock {
        Instant current = Instant.parse("2026-10-02T02:30:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Seoul"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
