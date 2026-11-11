package org.monitoring.catchholebackend.global.monitoring;

import java.time.Clock;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.domain.productusage.service.ProductUsageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @Slf4j
@ConditionalOnProperty(name = "product-usage.metrics.enabled", havingValue = "true")
public class ProductUsageMetricsScheduler {
    private final ProductUsageService usage;
    private final ProductUsageSnapshotRepository repository;
    private final ProductUsageMetrics metrics;
    private final Clock clock;
    @Autowired public ProductUsageMetricsScheduler(ProductUsageService usage, ProductUsageSnapshotRepository repository, ProductUsageMetrics metrics) {
        this(usage, repository, metrics, Clock.systemDefaultZone());
    }
    ProductUsageMetricsScheduler(ProductUsageService usage, ProductUsageSnapshotRepository repository, ProductUsageMetrics metrics, Clock clock) {
        this.usage = usage; this.repository = repository; this.metrics = metrics; this.clock = clock;
    }
    @Scheduled(fixedDelayString = "${product-usage.metrics.fixed-delay-ms:60000}")
    public void refresh() {
        try {
            LocalDateTime asOf = LocalDateTime.now(clock);
            usage.collect(asOf);
            metrics.updateSnapshot(repository.read(asOf));
        } catch (RuntimeException ex) {
            metrics.snapshotFailed();
            log.warn("제품 사용 집계 또는 지표 조회에 실패했습니다.");
        }
    }
}
