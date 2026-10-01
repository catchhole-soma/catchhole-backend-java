package org.monitoring.catchholebackend.domain.analysis.processor;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisMetricsSnapshotRepository;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisMetrics;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisResultReadyTracker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "analysis.metrics.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class AnalysisMetricsScheduler {
    private final AnalysisMetricsSnapshotRepository repository;
    private final AnalysisMetrics metrics;
    private final AnalysisResultReadyTracker tracker;
    private final AnalysisMetricsReconciler reconciler;
    private int offset;
    @Scheduled(fixedDelayString = "${analysis.metrics.fixed-delay-ms:15000}")
    public void refresh() {
        try { metrics.updateSnapshot(repository.read()); }
        catch (RuntimeException ex) { metrics.snapshotFailed(); log.warn("분석 상태 지표 조회에 실패했습니다."); }
        // 한 번에 최대 100개, 미완료 앞쪽이 뒷 회차 관측을 영구히 가리지 않도록 페이지를 순환한다.
        try {
            List<Object[]> pending = tracker.unresolved(offset);
            offset = pending.size() < 100 ? 0 : offset + 100;
            for (Object[] source : pending) {
                try { reconciler.submit((UUID) source[0], ((Number) source[1]).intValue()); }
                catch (RuntimeException ex) { log.warn("분석 결과 지표 재관측에 실패했습니다."); }
            }
        } catch (RuntimeException ex) { offset = 0; log.warn("분석 결과 지표 재관측 목록 조회에 실패했습니다."); }
    }
}
