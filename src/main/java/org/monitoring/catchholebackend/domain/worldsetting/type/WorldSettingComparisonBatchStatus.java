package org.monitoring.catchholebackend.domain.worldsetting.type;

/** 같은 회차·분류·raw 범위 후보 묶음의 2차 비교 처리 상태다. */
public enum WorldSettingComparisonBatchStatus {
    PROCESSING,
    COMPLETED,
    FAILED,
    REVIEW_REQUIRED
}
