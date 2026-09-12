package org.monitoring.catchholebackend.domain.character.repository;

public interface SettingCandidateBatchCounts {

    long getTotalCandidateCount();

    long getReviewedCandidateCount();

    long getPendingCandidateCount();

    long getMatchRequiredCandidateCount();

    long getAttentionRequiredCandidateCount();
    long getConfirmedCandidateCount();

    long getDismissedCandidateCount();

    long getDirectReviewCandidateCount();

    long getProcessingCandidateCount();

}
