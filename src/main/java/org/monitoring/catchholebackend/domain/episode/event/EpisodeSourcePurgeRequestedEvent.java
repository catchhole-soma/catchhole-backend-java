package org.monitoring.catchholebackend.domain.episode.event;

import java.util.UUID;

public record EpisodeSourcePurgeRequestedEvent(UUID requestId) {
}
