package org.monitoring.catchholebackend.global.monitoring;

import java.time.Clock;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "service-usage.metrics.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class ServiceUsageMetricsScheduler {
    private final ServiceUsageSnapshotRepository repository;
    private final ServiceUsageMetrics metrics;
    private final Clock clock;

    @Autowired
    public ServiceUsageMetricsScheduler(ServiceUsageSnapshotRepository repository, ServiceUsageMetrics metrics) {
        this(repository, metrics, Clock.systemDefaultZone());
    }

    ServiceUsageMetricsScheduler(ServiceUsageSnapshotRepository repository, ServiceUsageMetrics metrics, Clock clock) {
        this.repository = repository;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${service-usage.metrics.fixed-delay-ms:60000}")
    public void refresh() {
        try {
            LocalDateTime asOf = LocalDateTime.now(clock);
            metrics.updateSnapshot(repository.read(asOf));
        } catch (RuntimeException ex) {
            metrics.snapshotFailed();
            log.warn("서비스 이용 지표 조회에 실패했습니다.");
        }
    }
}
