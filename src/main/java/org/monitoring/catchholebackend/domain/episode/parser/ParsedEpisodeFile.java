package org.monitoring.catchholebackend.domain.episode.parser;

import java.util.Comparator;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public record ParsedEpisodeFile(
        MultipartFile episodeFile,
        List<ParsedEpisode> episodes
) {

    public int detectedEpisodeStartNo() {
        return episodes.stream()
                .map(ParsedEpisode::episodeNo)
                .min(Comparator.naturalOrder())
                .orElseThrow();
    }

    public int detectedEpisodeEndNo() {
        return episodes.stream()
                .map(ParsedEpisode::episodeNo)
                .max(Comparator.naturalOrder())
                .orElseThrow();
    }

    public int detectedEpisodeCount() {
        return episodes.size();
    }
}
