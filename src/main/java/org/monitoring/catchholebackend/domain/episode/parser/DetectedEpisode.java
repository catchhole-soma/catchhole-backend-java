package org.monitoring.catchholebackend.domain.episode.parser;

public record DetectedEpisode(
        int episodeNo,
        String title,
        String content
) {
}
