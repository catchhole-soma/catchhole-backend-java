package org.monitoring.catchholebackend.domain.episode.type;

public enum EpisodeAnalysisStatus {
    COMPLETED,
    REANALYSIS_REQUIRED,
    IN_PROGRESS,
    FAILED,
    ERROR_REVIEW_PREPARING,
    ERROR_REVIEW_PENDING,
    ERROR_REVIEW_HELD,
    ERROR_REVIEW_INVALIDATED
}
