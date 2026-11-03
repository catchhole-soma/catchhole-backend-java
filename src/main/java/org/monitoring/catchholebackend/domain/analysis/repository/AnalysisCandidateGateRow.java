package org.monitoring.catchholebackend.domain.analysis.repository;

import java.time.LocalDateTime;
import java.util.UUID;

/** Scalar projection shared by character/world continuation checks; contains no manuscript or JSON. */
public interface AnalysisCandidateGateRow {
    UUID getId();
    UUID getSourceJobId();
    LocalDateTime getUpdatedAt();
    LocalDateTime getSourceUpdatedAt();
    String getReviewMode();
    String getJobStatus();
    String getAnalysisMode();
    String getJournalStatus();
    LocalDateTime getAutomaticAppliedAt();
    LocalDateTime getReviewApplicationHeldAt();
    String getComparisonStatus();
    String getSuggestedOperation();
    String getHoldReason();
    Boolean getUserModified();
    String getReviewedApplicationMode();
    String getMatchStatus();
    String getCandidateKind();
    String getComparisonReviewReason();
    String getConsolidationStatus();
}
