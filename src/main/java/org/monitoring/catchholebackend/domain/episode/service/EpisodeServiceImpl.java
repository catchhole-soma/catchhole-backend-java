package org.monitoring.catchholebackend.domain.episode.service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeDetectionRequest;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeTitleUpdateRequest;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUpdateRequest;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUploadRequest;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeDetectionResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeSummaryResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeUploadResponse;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.exception.EpisodeErrorCode;
import org.monitoring.catchholebackend.domain.episode.mapper.EpisodeDetectionMapper;
import org.monitoring.catchholebackend.domain.episode.mapper.EpisodeMapper;
import org.monitoring.catchholebackend.domain.episode.parser.EpisodeFileParser;
import org.monitoring.catchholebackend.domain.episode.processor.EpisodeUploadProcessor;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.exception.UploadErrorCode;
import org.monitoring.catchholebackend.domain.upload.mapper.UploadMapper;
import org.monitoring.catchholebackend.domain.upload.parser.TextDocumentReader;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileRole;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.monitoring.catchholebackend.global.storage.StoredObject;
import org.monitoring.catchholebackend.global.storage.StoredTextObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
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
    private final EpisodeFileParser episodeFileParser;
    private final TextDocumentReader textDocumentReader;
    private final EpisodeDetectionMapper episodeDetectionMapper;
    private final UploadFileRepository uploadFileRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final UploadBatchRepository uploadBatchRepository;
    private final UploadMapper uploadMapper;

    @Override
    public List<EpisodeSummaryResponse> getEpisodes(Long memberId, UUID workId) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        List<Episode> episodes = episodeRepository.findAllByWorkIdAndStatusNotOrderByEpisodeNoDesc(
                work.getId(),
                EpisodeStatus.ARCHIVED
        );
        return toSummaryResponses(episodes);
    }

    @Override
    public EpisodeResponse getEpisode(Long memberId, UUID workId, UUID episodeId) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        Episode episode = getEpisodeInWork(episodeId, work);
        UploadFile sourceFile = episode.getSourceFileId() == null
                ? null
                : uploadFileRepository.findById(episode.getSourceFileId()).orElse(null);
        return episodeMapper.toResponse(
                episode,
                objectStorageService.getText(episode.getContentS3Key()),
                sourceFile
        );
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
        assertEpisodeIsNotAnalyzing(episode);

        StoredTextObject storedEpisodeContent = objectStorageService.replaceEpisodeContent(
                work.getId(),
                request.episodeNo(),
                request.content()
        );

        episode.updateContent(
                request.episodeNo(),
                request.title(),
                storedEpisodeContent.key(),
                storedEpisodeContent.versionId(),
                storedEpisodeContent.contentHash(),
                storedEpisodeContent.charCount()
        );
        refreshLatestEpisodeNo(work);
        return episodeMapper.toResponse(episode, request.content());
    }

    @Override
    @Transactional
    public EpisodeSummaryResponse updateEpisodeTitle(
            Long memberId,
            UUID workId,
            UUID episodeId,
            EpisodeTitleUpdateRequest request
    ) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        Episode episode = getEpisodeInWork(episodeId, work);
        episode.updateTitle(StringUtils.hasText(request.title()) ? request.title().trim() : null);
        return toSummaryResponse(episode);
    }

    @Override
    @Transactional
    public EpisodeSummaryResponse replaceEpisodeFile(
            Long memberId,
            UUID workId,
            UUID episodeId,
            MultipartFile file
    ) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        Episode episode = getEpisodeInWork(episodeId, work);
        assertEpisodeIsNotAnalyzing(episode);
        String content = textDocumentReader.readText(file);

        UploadBatch batch = uploadBatchRepository.save(UploadBatch.create(
                work, work.getMember(), UploadType.SINGLE_EPISODE, UploadSourceType.FILE));
        batch.startProcessing();
        batch.updateFileCount(1);
        StoredObject storedOriginal = objectStorageService.putUploadFile(
                batch.getId(), resolveOriginalFilename(file), readBytes(file), file.getContentType());
        UploadFile sourceFile = uploadFileRepository.save(uploadMapper.toEntity(
                batch,
                UploadFileRole.EPISODE,
                resolveOriginalFilename(file),
                file.getContentType(),
                objectStorageService.toStorageUrl(storedOriginal.key()),
                file.getSize()
        ));
        sourceFile.markEpisodesParsed(episode.getEpisodeNo(), episode.getEpisodeNo(), 1);

        StoredTextObject storedContent = objectStorageService.putEpisodeReplacementContent(
                work.getId(), episode.getEpisodeNo(), content);
        episode.replaceSourceFileAndContent(
                sourceFile.getId(),
                storedContent.key(),
                storedContent.versionId(),
                storedContent.contentHash(),
                storedContent.charCount()
        );
        batch.complete();
        return episodeMapper.toSummaryResponse(episode, sourceFile, null);
    }

    @Override
    @Transactional
    public void deleteEpisode(Long memberId, UUID workId, UUID episodeId) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        Episode episode = getEpisodeInWork(episodeId, work);
        assertEpisodeIsNotAnalyzing(episode);
        episode.archive();
        refreshLatestEpisodeNo(work);
    }

    @Override
    @Transactional
    public EpisodeUploadResponse uploadEpisodes(
            Long memberId,
            UUID workId,
            EpisodeUploadRequest uploadRequest,
            List<MultipartFile> sourceEpisodeFiles,
            MultipartFile attachedSettingBookFile
    ) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        return episodeUploadProcessor.processEpisodeUpload(
                work,
                uploadRequest,
                sourceEpisodeFiles,
                attachedSettingBookFile
        );
    }

    @Override
    public EpisodeDetectionResponse detectEpisodes(
            Long memberId,
            UUID workId,
            EpisodeDetectionRequest detectionRequest,
            List<MultipartFile> sourceEpisodeFiles
    ) {
        workRepository.getOwnedWork(workId, memberId);
        return episodeDetectionMapper.toResponse(
                detectionRequest.uploadType(),
                episodeFileParser.parseEpisodeFiles(
                        detectionRequest.uploadType(),
                        detectionRequest.singleEpisodeNo(),
                        detectionRequest.singleEpisodeTitle(),
                        sourceEpisodeFiles
                )
        );
    }

    private Episode getEpisodeInWork(UUID episodeId, Work work) {
        return episodeRepository.findByIdAndWorkIdAndStatusNot(episodeId, work.getId(), EpisodeStatus.ARCHIVED)
                .orElseThrow(() -> new AppException(EpisodeErrorCode.EPISODE_NOT_FOUND));
    }

    private void validateEpisodeNoForUpdate(Work work, Episode episode, int episodeNo) {
        if (episode.getEpisodeNo() != episodeNo
                && episodeRepository.existsByWorkIdAndEpisodeNoAndStatusNot(
                work.getId(), episodeNo, EpisodeStatus.ARCHIVED)) {
            throw new AppException(EpisodeErrorCode.EPISODE_DUPLICATED);
        }
    }

    private void refreshLatestEpisodeNo(Work work) {
        int latestEpisodeNo = episodeRepository.findFirstByWorkIdAndStatusNotOrderByEpisodeNoDesc(
                        work.getId(), EpisodeStatus.ARCHIVED)
                .map(Episode::getEpisodeNo)
                .orElse(0);
        work.updateLatestEpisodeNo(latestEpisodeNo);
    }

    private EpisodeSummaryResponse toSummaryResponse(Episode episode) {
        UploadFile sourceFile = episode.getSourceFileId() == null
                ? null
                : uploadFileRepository.findById(episode.getSourceFileId()).orElse(null);
        AnalysisJob latestAnalysisJob = sourceFile == null ? null : resolveLatestAnalysisJob(episode, sourceFile);
        return episodeMapper.toSummaryResponse(episode, sourceFile, latestAnalysisJob);
    }

    private List<EpisodeSummaryResponse> toSummaryResponses(List<Episode> episodes) {
        List<UUID> sourceFileIds = episodes.stream()
                .map(Episode::getSourceFileId)
                .filter(sourceFileId -> sourceFileId != null)
                .distinct()
                .toList();
        if (sourceFileIds.isEmpty()) {
            return episodes.stream()
                    .map(episode -> episodeMapper.toSummaryResponse(episode, null, null))
                    .toList();
        }

        Map<UUID, UploadFile> sourceFilesById = uploadFileRepository.findAllByIdIn(sourceFileIds).stream()
                .collect(Collectors.toMap(UploadFile::getId, Function.identity()));
        Set<UUID> batchIds = sourceFilesById.values().stream()
                .map(UploadFile::getBatch)
                .map(UploadBatch::getId)
                .collect(Collectors.toSet());
        List<UUID> episodeIds = episodes.stream()
                .map(Episode::getId)
                .toList();

        Map<UUID, Map<UUID, AnalysisJob>> latestEpisodeJobsByBatch = new HashMap<>();
        analysisJobRepository.findAllRelevantForEpisodeSummaries(batchIds, episodeIds)
                .forEach(analysisJob -> collectLatestAnalysisJob(
                        analysisJob,
                        latestEpisodeJobsByBatch
                ));

        return episodes.stream()
                .map(episode -> {
                    UploadFile sourceFile = sourceFilesById.get(episode.getSourceFileId());
                    AnalysisJob latestAnalysisJob = sourceFile == null
                            ? null
                            : resolveLatestAnalysisJob(
                                    episode,
                                    sourceFile.getBatch().getId(),
                                    latestEpisodeJobsByBatch
                            );
                    return episodeMapper.toSummaryResponse(episode, sourceFile, latestAnalysisJob);
                })
                .toList();
    }

    private void collectLatestAnalysisJob(
            AnalysisJob analysisJob,
            Map<UUID, Map<UUID, AnalysisJob>> latestEpisodeJobsByBatch
    ) {
        UUID batchId = analysisJob.getBatch().getId();
        latestEpisodeJobsByBatch
                .computeIfAbsent(batchId, ignored -> new HashMap<>())
                .putIfAbsent(analysisJob.getEpisode().getId(), analysisJob);
    }

    private AnalysisJob resolveLatestAnalysisJob(
            Episode episode,
            UUID batchId,
            Map<UUID, Map<UUID, AnalysisJob>> latestEpisodeJobsByBatch
    ) {
        return latestEpisodeJobsByBatch
                .getOrDefault(batchId, Map.of())
                .get(episode.getId());
    }

    private AnalysisJob resolveLatestAnalysisJob(Episode episode, UploadFile sourceFile) {
        UUID batchId = sourceFile.getBatch().getId();
        return analysisJobRepository
                .findFirstByEpisodeIdAndBatchIdAndJobTypeNotInOrderByCreatedAtDesc(
                        episode.getId(),
                        batchId,
                        Set.of(
                                AnalysisJobType.WORLD_SETTING_COMPARISON,
                                AnalysisJobType.CHARACTER_FACT_COMPARISON
                        )
                )
                .orElse(null);
    }

    private void assertEpisodeIsNotAnalyzing(Episode episode) {
        if (episode.getStatus() == EpisodeStatus.CHUNKING
                || episode.getStatus() == EpisodeStatus.CHUNKED
                || episode.getStatus() == EpisodeStatus.PREPROCESSING
                || episode.getStatus() == EpisodeStatus.PREPROCESSED
                || episode.getStatus() == EpisodeStatus.ANALYZING) {
            throw new AppException(EpisodeErrorCode.EPISODE_ANALYSIS_IN_PROGRESS);
        }
        if (episode.getSourceFileId() == null) {
            return;
        }
        Set<AnalysisJobStatus> activeStatuses = Set.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.RUNNING);
        uploadFileRepository.findById(episode.getSourceFileId())
                .map(UploadFile::getBatch)
                .map(UploadBatch::getId)
                .filter(batchId -> analysisJobRepository.existsByBatchIdAndEpisodeIsNullAndStatusIn(
                        batchId, activeStatuses)
                        || analysisJobRepository.existsByEpisodeIdAndBatchIdAndJobTypeNotInAndStatusIn(
                        episode.getId(),
                        batchId,
                        Set.of(
                                AnalysisJobType.WORLD_SETTING_COMPARISON,
                                AnalysisJobType.CHARACTER_FACT_COMPARISON
                        ),
                        activeStatuses
                ))
                .ifPresent(batchId -> {
                    throw new AppException(EpisodeErrorCode.EPISODE_ANALYSIS_IN_PROGRESS);
                });
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_READ_FAILED, exception);
        }
    }

    private String resolveOriginalFilename(MultipartFile file) {
        return textDocumentReader.requireOriginalFilename(file);
    }
}
