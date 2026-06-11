package org.monitoring.catchholebackend.domain.episode.service;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeSummaryResponse;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.exception.EpisodeErrorCode;
import org.monitoring.catchholebackend.domain.episode.mapper.EpisodeMapper;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.exception.WorkErrorCode;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EpisodeServiceImpl implements EpisodeService {

    private final EpisodeRepository episodeRepository;
    private final WorkRepository workRepository;
    private final EpisodeMapper episodeMapper;

    @Override
    public List<EpisodeSummaryResponse> getEpisodes(Long memberId, UUID workId) {
        Work work = getOwnedWork(workId, memberId);
        List<Episode> episodes = episodeRepository.findAllByWorkIdOrderByEpisodeNoDesc(work.getId());
        return episodeMapper.toSummaryResponseList(episodes);
    }

    @Override
    public EpisodeResponse getEpisode(Long memberId, UUID workId, UUID episodeId) {
        Work work = getOwnedWork(workId, memberId);
        Episode episode = episodeRepository.findByIdAndWorkId(episodeId, work.getId())
                .orElseThrow(() -> new AppException(EpisodeErrorCode.EPISODE_NOT_FOUND));
        return episodeMapper.toResponse(episode);
    }

    private Work getOwnedWork(UUID workId, Long memberId) {
        return workRepository.findByIdAndMemberId(workId, memberId)
                .orElseThrow(() -> new AppException(WorkErrorCode.WORK_NOT_FOUND));
    }
}
