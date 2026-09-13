package org.monitoring.catchholebackend.domain.analysis.type;

/** Job 성공과 별도로 후속 회차가 읽을 변경 기록의 완성·유효성을 표시한다. */
public enum AnalysisJournalStatus {
    PENDING,
    SEALED,
    INCOMPLETE,
    INVALIDATED
}
