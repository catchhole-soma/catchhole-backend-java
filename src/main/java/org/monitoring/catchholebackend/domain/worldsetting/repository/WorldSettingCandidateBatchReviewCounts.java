package org.monitoring.catchholebackend.domain.worldsetting.repository;

import java.util.UUID;

public interface WorldSettingCandidateBatchReviewCounts {

    UUID getBatchId();

    long getTotalCandidateCount();

    long getReviewedCandidateCount();

    long getPendingCandidateCount();
}
