package org.monitoring.catchholebackend.domain.episode.parser;

import org.monitoring.catchholebackend.domain.episode.type.EpisodeNumberDetectionStatus;

/**
 * 영구 저장 전 감지 결과. episodeNo가 null이면 사용자의 확정 번호가 있어야 저장할 수 있다.
 */
public record DetectedEpisode(
        Integer episodeNo,
        String title,
        String sourceHeading,
        String content,
        EpisodeNumberDetectionStatus numberDetectionStatus
) {
    public DetectedEpisode(int episodeNo, String title, String sourceHeading, String content) {
        this(episodeNo, title, sourceHeading, content, EpisodeNumberDetectionStatus.DETECTED);
    }

    public DetectedEpisode(int episodeNo, String title, String content) {
        this(episodeNo, title, null, content);
    }

    public static DetectedEpisode unresolved(
            String title,
            String content,
            EpisodeNumberDetectionStatus numberDetectionStatus
    ) {
        return new DetectedEpisode(null, title, null, content, numberDetectionStatus);
    }
}
