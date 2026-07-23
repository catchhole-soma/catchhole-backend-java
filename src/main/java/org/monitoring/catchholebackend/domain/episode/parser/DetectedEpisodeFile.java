package org.monitoring.catchholebackend.domain.episode.parser;

import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public record DetectedEpisodeFile(
        MultipartFile sourceFile,
        List<DetectedEpisode> detectedEpisodes
) {

    public int episodeCount() {
        return detectedEpisodes.size();
    }
}
