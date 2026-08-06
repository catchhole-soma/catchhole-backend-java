package org.monitoring.catchholebackend.domain.worldsetting.repository;

public interface WorldSettingCandidateBatchCounts {

    long getTotalCandidateCount();

    long getReviewedCandidateCount();

    long getPendingCandidateCount();

    long getPendingComparisonCount();

    long getProcessingComparisonCount();

    long getFailedComparisonCount();

    long getRecomparisonRequiredCount();
}
