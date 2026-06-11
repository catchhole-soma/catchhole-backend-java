package org.monitoring.catchholebackend.domain.episode.service;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeSummaryResponse;

public interface EpisodeService {

    List<EpisodeSummaryResponse> getEpisodes(Long memberId, UUID workId);

    EpisodeResponse getEpisode(Long memberId, UUID workId, UUID episodeId);
}
