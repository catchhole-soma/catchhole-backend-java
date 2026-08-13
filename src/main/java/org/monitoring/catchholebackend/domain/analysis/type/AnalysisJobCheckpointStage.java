package org.monitoring.catchholebackend.domain.analysis.type;

public enum AnalysisJobCheckpointStage {
    CHUNKS_READY,
    CHARACTER_CANDIDATES_SAVED,
    CHARACTER_COMPARISONS_FINISHED,
    WORLD_CANDIDATES_PUBLISHED,
    WORLD_COMPARISONS_FINISHED
}
