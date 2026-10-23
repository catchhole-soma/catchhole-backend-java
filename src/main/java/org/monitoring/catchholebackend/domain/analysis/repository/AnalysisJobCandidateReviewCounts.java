package org.monitoring.catchholebackend.domain.analysis.repository;

import java.util.UUID;

/** 후보 엔티티 대신 선택된 설정집 원본 작업별 숫자만 읽는다. */
public interface AnalysisJobCandidateReviewCounts {
    UUID getAnalysisJobId();
    long getTotalCandidateCount();
    long getReviewedCandidateCount();
    long getPendingCandidateCount();
    long getDirectReviewCandidateCount();
    long getProcessingCandidateCount();
}
