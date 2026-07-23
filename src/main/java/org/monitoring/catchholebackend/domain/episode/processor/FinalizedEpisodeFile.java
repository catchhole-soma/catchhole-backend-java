package org.monitoring.catchholebackend.domain.episode.processor;

import java.util.Comparator;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public record FinalizedEpisodeFile(
        MultipartFile sourceFile,
        List<FinalizedEpisode> finalizedEpisodes
) {

    public int episodeStartNo() {
        return finalizedEpisodes.stream()
                .map(FinalizedEpisode::episodeNo)
                .min(Comparator.naturalOrder())
                .orElseThrow();
    }

    public int episodeEndNo() {
        return finalizedEpisodes.stream()
                .map(FinalizedEpisode::episodeNo)
                .max(Comparator.naturalOrder())
                .orElseThrow();
    }

    public int episodeCount() {
        return finalizedEpisodes.size();
    }
}
