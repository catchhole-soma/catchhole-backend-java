package org.monitoring.catchholebackend.domain.episode.mapper;

import java.util.List;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeSummaryResponse;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.springframework.stereotype.Component;

@Component
public class EpisodeMapper {

    public EpisodeResponse toResponse(Episode episode, String content) {
        return new EpisodeResponse(
                episode.getId(),
                episode.getWork().getId(),
                episode.getSourceFileId(),
                episode.getEpisodeNo(),
                episode.getTitle(),
                content,
                episode.getContentS3Key(),
                episode.getContentS3Version(),
                episode.getContentHash(),
                episode.getCharCount(),
                episode.getStatus(),
                episode.getCreatedAt(),
                episode.getUpdatedAt()
        );
    }

    public EpisodeSummaryResponse toSummaryResponse(Episode episode) {
        return new EpisodeSummaryResponse(
                episode.getId(),
                episode.getEpisodeNo(),
                episode.getTitle(),
                episode.getCharCount(),
                episode.getStatus(),
                episode.getCreatedAt(),
                episode.getUpdatedAt()
        );
    }

    public List<EpisodeSummaryResponse> toSummaryResponseList(List<Episode> episodes) {
        return episodes.stream()
                .map(this::toSummaryResponse)
                .toList();
    }
}
