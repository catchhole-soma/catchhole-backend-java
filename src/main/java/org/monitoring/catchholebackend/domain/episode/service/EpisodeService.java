package org.monitoring.catchholebackend.domain.episode.service;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUpdateRequest;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUploadRequest;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeSummaryResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeUploadResponse;
import org.springframework.web.multipart.MultipartFile;

public interface EpisodeService {

    //TODO: 함수들이 어떤일들을 하는지에 대해서 조금이라도 작성해두자.
    List<EpisodeSummaryResponse> getEpisodes(Long memberId, UUID workId);

    EpisodeResponse getEpisode(Long memberId, UUID workId, UUID episodeId);

    EpisodeResponse updateEpisode(Long memberId, UUID workId, UUID episodeId, EpisodeUpdateRequest request);

    void deleteEpisode(Long memberId, UUID workId, UUID episodeId);

    EpisodeUploadResponse uploadEpisodes(
            Long memberId,
            UUID workId,
            EpisodeUploadRequest request,
            List<MultipartFile> episodeFiles,
            MultipartFile settingBookFile
    );
}
