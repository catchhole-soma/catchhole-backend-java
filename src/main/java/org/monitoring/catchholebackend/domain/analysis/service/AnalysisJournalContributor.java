package org.monitoring.catchholebackend.domain.analysis.service;

import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;

/** 실행 종료 전 비반영·미해결 판단도 당시 값으로 기록한다. S0 조회와 분리해 순환 의존을 피한다. */
public interface AnalysisJournalContributor {
    String domain();

    void finalizeChanges(AnalysisJob job);
}
