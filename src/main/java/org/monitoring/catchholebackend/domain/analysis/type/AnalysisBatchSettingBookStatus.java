package org.monitoring.catchholebackend.domain.analysis.type;

/** 설정집 카드의 표시 상태. 상세 Job/journal 상태를 목록에 전달하지 않는다. */
public enum AnalysisBatchSettingBookStatus {
    PENDING, IN_PROGRESS, COMPLETED, INTERRUPTED, FAILED, CANCELED, INVALIDATED
}
