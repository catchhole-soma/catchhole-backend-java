package org.monitoring.catchholebackend.domain.episode.service;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUpdateRequest;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUploadRequest;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeSummaryResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeUploadResponse;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.exception.EpisodeErrorCode;
import org.monitoring.catchholebackend.domain.episode.mapper.EpisodeMapper;
import org.monitoring.catchholebackend.domain.episode.processor.EpisodeUploadProcessor;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.monitoring.catchholebackend.global.storage.StoredTextObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EpisodeServiceImpl implements EpisodeService {

    private final EpisodeRepository episodeRepository;
    private final WorkRepository workRepository;
    private final EpisodeMapper episodeMapper;
    private final ObjectStorageService objectStorageService;
    private final EpisodeUploadProcessor episodeUploadProcessor;

    @Override
    public List<EpisodeSummaryResponse> getEpisodes(Long memberId, UUID workId) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        List<Episode> episodes = episodeRepository.findAllByWorkIdOrderByEpisodeNoDesc(work.getId());
        return episodeMapper.toSummaryResponseList(episodes);
    }

    @Override
    public EpisodeResponse getEpisode(Long memberId, UUID workId, UUID episodeId) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        Episode episode = getEpisodeInWork(episodeId, work);
        return episodeMapper.toResponse(episode, objectStorageService.getText(episode.getContentS3Key()));
    }

    @Override
    @Transactional
    public EpisodeResponse updateEpisode(
            Long memberId,
            UUID workId,
            UUID episodeId,
            EpisodeUpdateRequest request
    ) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        Episode episode = getEpisodeInWork(episodeId, work);
        validateEpisodeNoForUpdate(work, episode, request.episodeNo());

        StoredTextObject storedContent = objectStorageService.replaceEpisodeContent(
                work.getId(),
                request.episodeNo(),
                episode.getContentS3Key(),
                request.content()
        );

        episode.updateContent(
                request.episodeNo(),
                request.title(),
                storedContent.key(),
                storedContent.versionId(),
                storedContent.contentHash(),
                storedContent.charCount()
        );
        refreshLatestEpisodeNo(work);
        return episodeMapper.toResponse(episode, request.content());
    }

    @Override
    @Transactional
    public void deleteEpisode(Long memberId, UUID workId, UUID episodeId) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        Episode episode = getEpisodeInWork(episodeId, work);
        objectStorageService.delete(episode.getContentS3Key());
        episodeRepository.delete(episode);
        episodeRepository.flush();
        refreshLatestEpisodeNo(work);
    }

    @Override
    @Transactional
    public EpisodeUploadResponse uploadEpisodes(
            Long memberId,
            UUID workId,
            EpisodeUploadRequest request,
            List<MultipartFile> episodeFiles,
            MultipartFile settingBookFile
    ) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        return episodeUploadProcessor.upload(work, request, episodeFiles, settingBookFile);
    }

    private Episode getEpisodeInWork(UUID episodeId, Work work) {
        return episodeRepository.findByIdAndWorkId(episodeId, work.getId())
                .orElseThrow(() -> new AppException(EpisodeErrorCode.EPISODE_NOT_FOUND));
    }

    private void validateEpisodeNoForUpdate(Work work, Episode episode, int episodeNo) {
        if (episode.getEpisodeNo() != episodeNo
                && episodeRepository.existsByWorkIdAndEpisodeNo(work.getId(), episodeNo)) {
            throw new AppException(EpisodeErrorCode.EPISODE_DUPLICATED);
        }
    }

    private void refreshLatestEpisodeNo(Work work) {
        int latestEpisodeNo = episodeRepository.findFirstByWorkIdOrderByEpisodeNoDesc(work.getId())
                .map(Episode::getEpisodeNo)
                .orElse(0);
        work.updateLatestEpisodeNo(latestEpisodeNo);
    }
}
