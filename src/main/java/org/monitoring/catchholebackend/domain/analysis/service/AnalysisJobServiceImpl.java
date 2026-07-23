package org.monitoring.catchholebackend.domain.analysis.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.dto.request.AnalysisJobCreateRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisJobResponse;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisJobMapper;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisJobServiceImpl implements AnalysisJobService {

    private final AnalysisJobRepository analysisJobRepository;
    private final WorkRepository workRepository;
    private final UploadBatchRepository uploadBatchRepository;
    private final UploadFileRepository uploadFileRepository;
    private final AnalysisJobMapper analysisJobMapper;
    private final EpisodeRepository episodeRepository;

    @Override
    @Transactional
    public AnalysisJobResponse createAnalysisJob(Long memberId, UUID workId, AnalysisJobCreateRequest request) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        UploadBatch batch = getBatchInWork(request.batchId(), work);
        Episode episode = request.episodeId() == null ? null : getEpisodeInBatch(request.episodeId(), work, batch);
        if (hasActiveAnalysisJob(batch, episode)) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_ALREADY_IN_PROGRESS);
        }

        AnalysisJob analysisJob = AnalysisJob.create(work, batch, episode, request.jobType());
        AnalysisJob savedAnalysisJob = analysisJobRepository.save(analysisJob);

        return toResponse(savedAnalysisJob);
    }

    @Override
    public List<AnalysisJobResponse> getAnalysisJobs(Long memberId, UUID workId) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        List<AnalysisJob> analysisJobs = analysisJobRepository.findAllByWorkIdOrderByCreatedAtDesc(work.getId());
        Map<UUID, List<UploadFile>> uploadFilesByBatchId = getUploadFilesByBatchId(analysisJobs);
        Map<UUID, List<Episode>> episodesByJobId = analysisJobs.stream()
                .collect(Collectors.toMap(
                        AnalysisJob::getId,
                        analysisJob -> getTargetEpisodes(
                                analysisJob,
                                analysisJob.getBatch() == null
                                        ? List.of()
                                        : uploadFilesByBatchId.getOrDefault(analysisJob.getBatch().getId(), List.of())
                        )
                ));
        return analysisJobMapper.toResponseList(analysisJobs, uploadFilesByBatchId, episodesByJobId);
    }

    @Override
    public AnalysisJobResponse getAnalysisJob(Long memberId, UUID workId, UUID analysisJobId) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        AnalysisJob analysisJob = analysisJobRepository.findByIdAndWorkId(analysisJobId, work.getId())
                .orElseThrow(() -> new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_NOT_FOUND , "해당 아이디가 존재하지 않습니다."));
        return toResponse(analysisJob);
    }

    @Override
    @Transactional
    public List<AnalysisJobResponse> retryFailedAnalysisJob(Long memberId, UUID workId, UUID analysisJobId) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        AnalysisJob failedJob = analysisJobRepository.findByIdAndWorkId(analysisJobId, work.getId())
                .orElseThrow(() -> new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_NOT_FOUND));
        if (failedJob.getStatus() != AnalysisJobStatus.FAILED) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_STATUS_CONFLICT);
        }

        List<UploadFile> uploadFiles = getUploadFiles(failedJob);
        List<Episode> failedEpisodes = getTargetEpisodes(failedJob, uploadFiles).stream()
                .filter(episode -> episode.getStatus() == EpisodeStatus.FAILED)
                .toList();
        if (failedEpisodes.isEmpty()) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_TARGET_NOT_FOUND);
        }

        return failedEpisodes.stream()
                .map(episode -> getOrCreateRetryJob(work, failedJob, episode))
                .map(this::toResponse)
                .toList();
    }

    private AnalysisJobResponse toResponse(AnalysisJob analysisJob) {
        List<UploadFile> uploadFiles = getUploadFiles(analysisJob);
        return analysisJobMapper.toResponse(
                analysisJob,
                uploadFiles,
                getTargetEpisodes(analysisJob, uploadFiles)
        );
    }

    private List<Episode> getTargetEpisodes(AnalysisJob analysisJob, List<UploadFile> uploadFiles) {
        if (analysisJob.getEpisode() != null) {
            return List.of(analysisJob.getEpisode());
        }
        List<UUID> sourceFileIds = uploadFiles.stream()
                .map(UploadFile::getId)
                .toList();
        return sourceFileIds.isEmpty()
                ? List.of()
                : episodeRepository.findAllBySourceFileIdInAndStatusNotOrderByEpisodeNoAsc(
                        sourceFileIds, EpisodeStatus.ARCHIVED);
    }

    private UploadBatch getBatchInWork(UUID batchId, Work work) {
        if (batchId == null) {
            return null;
        }
        return uploadBatchRepository.findByIdAndWorkId(batchId, work.getId())
                .orElseThrow(() -> new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_TARGET_NOT_FOUND));
    }

    private Episode getEpisodeInBatch(UUID episodeId, Work work, UploadBatch batch) {
        Episode episode = episodeRepository
                .findByIdAndWorkIdAndStatusNot(episodeId, work.getId(), EpisodeStatus.ARCHIVED)
                .orElseThrow(() -> new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_TARGET_NOT_FOUND));
        UploadFile sourceFile = episode.getSourceFileId() == null
                ? null
                : uploadFileRepository.findById(episode.getSourceFileId()).orElse(null);
        if (sourceFile == null || !sourceFile.getBatch().getId().equals(batch.getId())) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_TARGET_NOT_FOUND);
        }
        return episode;
    }

    private boolean hasActiveAnalysisJob(UploadBatch batch, Episode episode) {
        Set<AnalysisJobStatus> activeStatuses = Set.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.RUNNING);
        if (episode == null) {
            return analysisJobRepository.existsByBatchIdAndStatusIn(batch.getId(), activeStatuses);
        }
        return analysisJobRepository.existsByBatchIdAndEpisodeIsNullAndStatusIn(batch.getId(), activeStatuses)
                || analysisJobRepository.existsByEpisodeIdAndBatchIdAndStatusIn(
                episode.getId(), batch.getId(), activeStatuses);
    }

    private AnalysisJob getOrCreateRetryJob(Work work, AnalysisJob failedJob, Episode episode) {
        Set<AnalysisJobStatus> activeStatuses = Set.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.RUNNING);
        return analysisJobRepository
                .findFirstByEpisodeIdAndBatchIdAndStatusInOrderByCreatedAtDesc(
                        episode.getId(), failedJob.getBatch().getId(), activeStatuses)
                .orElseGet(() -> analysisJobRepository.save(AnalysisJob.create(
                        work, failedJob.getBatch(), episode, failedJob.getJobType())));
    }

    private List<UploadFile> getUploadFiles(AnalysisJob analysisJob) {
        if (analysisJob.getBatch() == null) {
            return List.of();
        }
        return uploadFileRepository.findAllByBatchIdOrderByCreatedAtAsc(analysisJob.getBatch().getId());
    }

    private Map<UUID, List<UploadFile>> getUploadFilesByBatchId(List<AnalysisJob> analysisJobs) {
        List<UUID> batchIds = analysisJobs.stream()
                .map(AnalysisJob::getBatch)
                .filter(batch -> batch != null)
                .map(UploadBatch::getId)
                .distinct()
                .toList();
        if (batchIds.isEmpty()) {
            return Map.of();
        }
        return uploadFileRepository.findAllByBatchIdIn(batchIds)
                .stream()
                .collect(Collectors.groupingBy(uploadFile -> uploadFile.getBatch().getId()));
    }
}
