package org.monitoring.catchholebackend.domain.episode.processor;

public record FinalizedEpisode(
        int episodeNo,
        String title,
        String content
) {
}
