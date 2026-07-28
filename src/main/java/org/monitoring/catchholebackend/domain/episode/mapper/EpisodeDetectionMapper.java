package org.monitoring.catchholebackend.domain.episode.mapper;

import java.util.ArrayList;
import java.util.List;
import org.monitoring.catchholebackend.domain.episode.dto.response.DetectedEpisodeResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeDetectionResponse;
import org.monitoring.catchholebackend.domain.episode.parser.DetectedEpisode;
import org.monitoring.catchholebackend.domain.episode.parser.DetectedEpisodeFile;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeUploadType;
import org.springframework.stereotype.Component;

@Component
public class EpisodeDetectionMapper {

    public EpisodeDetectionResponse toResponse(
            EpisodeUploadType uploadType,
            List<DetectedEpisodeFile> detectedEpisodeFiles
    ) {
        List<DetectedEpisodeResponse> detectedEpisodes = new ArrayList<>();
        int detectionOrder = 0;
        for (int sourceFileIndex = 0; sourceFileIndex < detectedEpisodeFiles.size(); sourceFileIndex++) {
            for (DetectedEpisode detectedEpisode
                    : detectedEpisodeFiles.get(sourceFileIndex).detectedEpisodes()) {
                detectedEpisodes.add(toDetectedEpisodeResponse(
                        detectionOrder,
                        sourceFileIndex,
                        detectedEpisode
                ));
                detectionOrder++;
            }
        }
        return new EpisodeDetectionResponse(
                uploadType,
                detectedEpisodes.size(),
                detectedEpisodes.stream().mapToInt(DetectedEpisodeResponse::charCount).sum(),
                detectedEpisodes
        );
    }

    private DetectedEpisodeResponse toDetectedEpisodeResponse(
            int detectionOrder,
            int sourceFileIndex,
            DetectedEpisode detectedEpisode
    ) {
        return new DetectedEpisodeResponse(
                detectionOrder,
                sourceFileIndex,
                detectedEpisode.episodeNo(),
                detectedEpisode.title(),
                detectedEpisode.sourceHeading(),
                countNonWhitespaceCharacters(detectedEpisode.content()),
                detectedEpisode.content()
        );
    }

    private int countNonWhitespaceCharacters(String content) {
        return Math.toIntExact(content.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .count());
    }
}
