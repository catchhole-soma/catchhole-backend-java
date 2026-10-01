package org.monitoring.catchholebackend.domain.analysis.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisMetrics;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisMetricsReconciler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisMetricsCommittedListener {
    private final AnalysisMetrics metrics;
    private final AnalysisMetricsReconciler reconciler;
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void committed(AnalysisMetricsEvent event) {
        try {
            if (event.kind().equals("reconcile")) reconciler.submit(event.sourceId(), event.attemptNo());
            else metrics.record(event);
        } catch (RuntimeException ex) {
            // 별도 관측 transaction 실패는 이미 커밋한 분석/API 결과를 변경하지 않는다. scheduler가 재시도한다.
            log.warn("분석 결과 지표 관측에 실패했습니다.");
        }
    }
}
