package org.monitoring.catchholebackend.domain.episode.parser;

public record DetectedEpisode(
        int episodeNo,
        String title,
        String sourceHeading,
        String content
) {
    public DetectedEpisode(int episodeNo, String title, String content) {
        this(episodeNo, title, null, content);
    }
}
