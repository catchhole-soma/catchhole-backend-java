package org.monitoring.catchholebackend.domain.worldsetting.repository;

public interface WorldSettingCandidateBatchCounts extends WorldSettingTokenInterruptedCounts {

    long getTotalCandidateCount();

    long getReviewedCandidateCount();

    long getPendingCandidateCount();

    long getPendingComparisonCount();

    long getProcessingComparisonCount();

    long getFailedComparisonCount();

    long getRecomparisonRequiredCount();

    long getConflictCandidateCount();
    long getConfirmedCandidateCount();

    long getDismissedCandidateCount();

    long getDirectReviewCandidateCount();

    long getProcessingCandidateCount();

}
