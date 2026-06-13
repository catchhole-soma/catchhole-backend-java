package org.monitoring.catchholebackend.domain.episode.parser;

public record ParsedEpisode(
        int episodeNo,
        String title,
        String content
) {
}
