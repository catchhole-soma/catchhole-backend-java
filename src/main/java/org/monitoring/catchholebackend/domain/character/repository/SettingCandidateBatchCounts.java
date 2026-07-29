package org.monitoring.catchholebackend.domain.character.repository;

public interface SettingCandidateBatchCounts {

    long getTotalCandidateCount();

    long getReviewedCandidateCount();

    long getPendingCandidateCount();

    long getMatchRequiredCandidateCount();
}
