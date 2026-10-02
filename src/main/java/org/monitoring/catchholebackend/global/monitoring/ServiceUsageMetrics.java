package org.monitoring.catchholebackend.global.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 서비스 이용 지표의 DB 조회 결과를 보관하며 scrape 중에는 DB에 접근하지 않는다. */
@Slf4j
@Component
public class ServiceUsageMetrics {
    private final Clock clock;
    private final AtomicReference<CachedSnapshot> snapshot = new AtomicReference<>(
            new CachedSnapshot(Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, 0, 0));

    @Autowired
    public ServiceUsageMetrics(MeterRegistry registry) {
        this(registry, Clock.systemDefaultZone());
    }

    ServiceUsageMetrics(MeterRegistry registry, Clock clock) {
        this.clock = clock;
        try {
            Gauge.builder("catchhole.service.members", this, m -> m.snapshot.get().members()).register(registry);
            Gauge.builder("catchhole.service.analysis.users.7d", this, m -> m.snapshot.get().analysisUsers7d()).register(registry);
            Gauge.builder("catchhole.service.analysis.requests.24h", this, m -> m.snapshot.get().requests24h()).register(registry);
            Gauge.builder("catchhole.service.analysis.request.episodes.average.24h", this,
                    m -> m.snapshot.get().averageEpisodes24h()).register(registry);
            Gauge.builder("catchhole.service.usage.snapshot.success", this, m -> m.snapshot.get().success()).register(registry);
            Gauge.builder("catchhole.service.usage.snapshot.last.success.timestamp", this,
                    m -> m.snapshot.get().lastSuccessTimestampSeconds()).baseUnit("seconds").register(registry);
            Gauge.builder("catchhole.service.usage.requests.24h.complete", this,
                    m -> m.snapshot.get().requests24hComplete()).register(registry);
        } catch (RuntimeException ex) {
            log.warn("서비스 이용 지표 등록에 실패했습니다.");
        }
    }

    public void updateSnapshot(ServiceUsageSnapshotRepository.Snapshot values) {
        boolean complete = values.requestHistoryComplete();
        snapshot.set(new CachedSnapshot(values.members(), values.analysisUsers7d(),
                complete ? values.requests24h() : Double.NaN,
                complete ? values.averageEpisodes24h() : Double.NaN,
                1, clock.instant().getEpochSecond(), complete ? 1 : 0));
    }

    public void snapshotFailed() {
        snapshot.updateAndGet(previous -> new CachedSnapshot(previous.members(), previous.analysisUsers7d(),
                previous.requests24h(), previous.averageEpisodes24h(), 0,
                previous.lastSuccessTimestampSeconds(), previous.requests24hComplete()));
    }

    private record CachedSnapshot(double members, double analysisUsers7d, double requests24h,
            double averageEpisodes24h, double success, double lastSuccessTimestampSeconds,
            double requests24hComplete) {}
}
