package org.monitoring.catchholebackend.global.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.monitoring.catchholebackend.domain.productusage.service.ProductUsageService;

@DisplayName("제품 사용 Gauge의 신선도와 기간 교체")
class ProductUsageMetricsTest {
    final LocalDateTime now = LocalDateTime.of(2026, 11, 11, 12, 0);
    final ZoneId zone = ZoneId.of("Asia/Seoul");
    final Clock clock = Clock.fixed(now.atZone(zone).toInstant(), zone);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final ProductUsageMetrics metrics = new ProductUsageMetrics(registry, clock);

    @Test @DisplayName("시작 전 데이터는 NaN이고 성공으로 표시하지 않는다")
    void startsUnavailable() {
        assertThat(value("catchhole.product.analysis.members", "period", "1d")).isNaN();
        assertThat(value("catchhole.product.usage.snapshot.success")).isZero();
        assertThat(value("catchhole.product.usage.snapshot.last.success.timestamp")).isZero();
    }

    @Test @DisplayName("읽은 시각이 아니라 영속 집계 성공 시각을 노출한다")
    void usesCollectionTimeAndShowsPartialCoverage() {
        var collected = now.minusMinutes(4);
        metrics.updateSnapshot(snapshot(now, now.minusHours(1), collected));
        assertThat(value("catchhole.product.analysis.members", "period", "1d")).isEqualTo(2);
        assertThat(value("catchhole.product.analysis.members", "period", "month")).isEqualTo(3);
        assertThat(value("catchhole.product.period.complete", "period", "1d")).isZero();
        assertThat(value("catchhole.product.usage.snapshot.last.success.timestamp"))
                .isEqualTo(collected.atZone(zone).toEpochSecond());
    }

    @Test @DisplayName("집계 실패 뒤 읽기로 성공을 덮지 않고 이전 성공 시각을 보존한다")
    void collectionFailureStopsSnapshotRead() {
        metrics.updateSnapshot(snapshot(now, now.minusDays(40), now.minusSeconds(30)));
        var failing = new ProductUsageService(null, null) {
            @Override public void collect(LocalDateTime asOf) { throw new IllegalStateException("test failure"); }
        };
        var unread = new ProductUsageSnapshotRepository(null) {
            @Override public ProductUsageSnapshot read(LocalDateTime asOf) { throw new AssertionError("실패 뒤 읽으면 안 됩니다."); }
        };
        new ProductUsageMetricsScheduler(failing, unread, metrics, clock).refresh();
        assertThat(value("catchhole.product.usage.snapshot.success")).isZero();
        assertThat(value("catchhole.product.usage.snapshot.last.success.timestamp"))
                .isEqualTo(now.minusSeconds(30).atZone(zone).toEpochSecond());
    }

    @Test @DisplayName("날짜가 바뀌면 오래된 label은 제거하고 미래 날짜는 노출하지 않는다")
    void rotatesBoundedHistoryWithoutIdentityLabels() {
        metrics.updateSnapshot(snapshot(now, now.minusDays(40), now));
        assertThat(registry.find("catchhole.product.daily.analysis.members").tag("date", "2026-11-11").gauge()).isNotNull();
        LocalDateTime later = LocalDateTime.of(2027, 2, 1, 0, 0);
        var daily = List.of(
                new ProductUsageSnapshot.Day(now.toLocalDate(), 1, 1, 1, 1, 0, 0, 0),
                new ProductUsageSnapshot.Day(later.toLocalDate(), 2, 2, 2, 2, 1, 0, 1),
                new ProductUsageSnapshot.Day(later.toLocalDate().plusDays(1), 2, 2, 2, 2, 1, 0, 1));
        metrics.updateSnapshot(new ProductUsageSnapshot(later, now, later, daily, List.of()));
        assertThat(registry.find("catchhole.product.daily.analysis.members").tag("date", "2026-11-11").gauge()).isNull();
        assertThat(registry.find("catchhole.product.daily.analysis.members").tag("date", "2027-02-02").gauge()).isNull();
        assertThat(registry.find("catchhole.product.daily.analysis.members").tag("date", "2027-02-01").gauge()).isNotNull();
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag -> assertThat(tag.getKey()).isIn("period", "date", "month")));
    }

    private ProductUsageSnapshot snapshot(LocalDateTime asOf, LocalDateTime start, LocalDateTime collected) {
        return new ProductUsageSnapshot(asOf, start, collected,
                List.of(new ProductUsageSnapshot.Day(asOf.toLocalDate(), 2, 3, 4, 5, 2, 1, 3)),
                List.of(new ProductUsageSnapshot.Month(asOf.toLocalDate().withDayOfMonth(1), 3, 6, 3, 1, 4)));
    }

    @Test @DisplayName("실제 Prometheus 출력 이름과 기간 label이 대시보드 계약과 일치한다")
    void exportsPrometheusSeries() {
        var prometheus = new io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
                io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT);
        try {
            var exported = new ProductUsageMetrics(prometheus, clock);
            exported.updateSnapshot(snapshot(now, now.minusDays(40), now));
            String text = prometheus.scrape();
            assertThat(text).contains("catchhole_product_analysis_members{period=\"1d\"} 2.0");
            assertThat(text).contains("catchhole_product_usage_snapshot_last_success_timestamp_seconds ");
            assertThat(text).contains("catchhole_product_monthly_completed_requests{month=\"2026-11\"} 6.0");
        } finally {
            prometheus.close();
        }
    }
    private double value(String name, String... tags) { return registry.get(name).tags(tags).gauge().value(); }
}
