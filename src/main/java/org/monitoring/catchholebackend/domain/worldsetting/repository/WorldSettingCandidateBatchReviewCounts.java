package org.monitoring.catchholebackend.domain.worldsetting.repository;

import java.util.UUID;

public interface WorldSettingCandidateBatchReviewCounts extends WorldSettingTokenInterruptedCounts {

    UUID getBatchId();

    long getTotalCandidateCount();

    long getReviewedCandidateCount();

    long getPendingCandidateCount();

    long getPendingComparisonCount();

    long getProcessingComparisonCount();

}
