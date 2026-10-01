package org.monitoring.catchholebackend.domain.worldsetting.repository;

public interface WorldSettingTokenInterruptedCounts {

    long getTokenInterruptedComparisonCount();

    long getBlockedTokenInterruptedComparisonCount();

    default boolean canResumeTokenInterruptedComparisons() {
        // 일괄 재개 API는 대상 중 하나라도 순차 분석 또는 자동 반영 중이면 전체를 거절한다.
        return getTokenInterruptedComparisonCount() > 0
                && getBlockedTokenInterruptedComparisonCount() == 0;
    }
}
