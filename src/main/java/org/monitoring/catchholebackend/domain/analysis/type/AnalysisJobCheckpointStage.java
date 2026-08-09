package org.monitoring.catchholebackend.domain.analysis.type;

public enum AnalysisJobCheckpointStage {
    CHUNKS_READY,
    CHARACTER_CANDIDATES_SAVED,
    WORLD_CANDIDATES_PUBLISHED,
    WORLD_COMPARISONS_FINISHED
}
