package org.monitoring.catchholebackend.domain.analysis.repository;

import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;

public record AnalysisJobProgressEpisodeRow(
        UUID analysisJobId, UUID id, Integer episodeNo, String title, EpisodeStatus status, LocalDateTime updatedAt
) {}
