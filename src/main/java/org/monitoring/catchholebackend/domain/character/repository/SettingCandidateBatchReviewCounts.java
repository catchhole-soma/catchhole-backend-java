package org.monitoring.catchholebackend.domain.character.repository;

import java.util.UUID;

public interface SettingCandidateBatchReviewCounts {

    UUID getBatchId();

    long getTotalCandidateCount();

    long getReviewedCandidateCount();

    long getPendingCandidateCount();
}
