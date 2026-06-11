package org.monitoring.catchholebackend.domain.episode.service;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUploadRequest;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeSummaryResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeUploadResponse;
import org.springframework.web.multipart.MultipartFile;

public interface EpisodeService {

    List<EpisodeSummaryResponse> getEpisodes(Long memberId, UUID workId);

    EpisodeResponse getEpisode(Long memberId, UUID workId, UUID episodeId);

    EpisodeUploadResponse uploadEpisodes(
            Long memberId,
            UUID workId,
            EpisodeUploadRequest request,
            List<MultipartFile> episodeFiles,
            MultipartFile settingBookFile
    );
}
