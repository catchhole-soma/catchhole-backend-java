package org.monitoring.catchholebackend.domain.character.repository;

import java.util.UUID;

public record CharacterTimelineEpisodeCount(
        UUID episodeId,
        int episodeNo,
        long factCount
) {
}
