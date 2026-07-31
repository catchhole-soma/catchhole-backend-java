package org.monitoring.catchholebackend.domain.analysis.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.dto.request.AnalysisJobCreateRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisBatchJobGroupResponse;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisBatchSummaryResponse;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisJobResponse;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisBatchMapper;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisJobMapper;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisBatchPageRow;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisBatchStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateBatchReviewCounts;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
    private final AnalysisBatchMapper analysisBatchMapper;
    private final EpisodeRepository episodeRepository;
    private final SettingCandidateRepository settingCandidateRepository;

    @Override
    @Transactional
    public List<AnalysisJobResponse> createAnalysisJobs(
            Long memberId,
            UUID workId,
            AnalysisJobCreateRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        UploadBatch batch = getBatchInWork(request.batchId(), work);
        Episode episode = request.episodeId() == null ? null : getEpisodeInBatch(request.episodeId(), work, batch);

        List<UploadFile> uploadFiles = getUploadFiles(batch);
        List<Episode> targetEpisodes = episode == null
                ? findCurrentBatchEpisodes(uploadFiles)
                : List.of(episode);
        if (targetEpisodes.isEmpty()) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_TARGET_NOT_FOUND);
        }

        if (targetEpisodes.stream().anyMatch(targetEpisode -> hasActiveAnalysisJob(batch, targetEpisode))) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_ALREADY_IN_PROGRESS);
        }

        deleteSupersededPendingCandidates(
                work.getId(),
                batch.getId(),
                request.jobType(),
                targetEpisodes
        );
        List<AnalysisJob> analysisJobs = targetEpisodes.stream()
                .map(targetEpisode -> AnalysisJob.create(work, batch, targetEpisode, request.jobType()))
                .toList();
        return analysisJobRepository.saveAll(analysisJobs).stream()
                .map(savedJob -> analysisJobMapper.toResponse(
                        savedJob,
                        uploadFiles,
                        List.of(savedJob.getEpisode())
                ))
                .toList();
    }

    @Override
    public List<AnalysisJobResponse> getAnalysisJobs(Long memberId, UUID workId) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        List<AnalysisJob> analysisJobs =
                analysisJobRepository.findAllWithTargetsByWorkIdOrderByCreatedAtDesc(work.getId());
        Map<UUID, List<UploadFile>> uploadFilesByBatchId = getUploadFilesByBatchId(analysisJobs);
        Map<UUID, List<Episode>> episodesByJobId = analysisJobs.stream()
                .collect(Collectors.toMap(
                        AnalysisJob::getId,
                        this::getTargetEpisodes
                ));
        return analysisJobMapper.toResponseList(analysisJobs, uploadFilesByBatchId, episodesByJobId);
    }

    @Override
    public PageResponse<AnalysisBatchSummaryResponse> getAnalysisBatches(
            Long memberId,
            UUID workId,
            int page,
            int size
    ) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        Page<AnalysisBatchPageRow> batchPage =
                analysisJobRepository.findBatchPage(work.getId(), PageRequest.of(page, size));
        List<UUID> batchIds = batchPage.getContent().stream()
                .map(AnalysisBatchPageRow::getBatchId)
                .toList();
        if (batchIds.isEmpty()) {
            return PageResponse.from(batchPage, List.of());
        }

        Map<UUID, List<AnalysisJob>> jobsByBatchId = analysisJobRepository
                .findAllByWorkIdAndBatchIdInOrderByCreatedAtDescIdDesc(work.getId(), batchIds)
                .stream()
                .collect(Collectors.groupingBy(
                        job -> job.getBatch().getId(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        Map<UUID, SettingCandidateBatchReviewCounts> candidateCountsByBatchId =
                settingCandidateRepository.countReviewSummaryByBatchIds(
                                work.getId(),
                                batchIds,
                                SettingCandidateReviewStatus.PENDING_REVIEW
                        )
                        .stream()
                        .collect(Collectors.toMap(
                                SettingCandidateBatchReviewCounts::getBatchId,
                                counts -> counts
                        ));

        List<AnalysisBatchSummaryResponse> responses = batchPage.getContent().stream()
                .map(row -> toBatchSummary(
                        row,
                        jobsByBatchId.getOrDefault(row.getBatchId(), List.of()),
                        candidateCountsByBatchId.get(row.getBatchId())
                ))
                .toList();
        return PageResponse.from(batchPage, responses);
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
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        AnalysisJob failedJob = analysisJobRepository.findByIdAndWorkId(analysisJobId, work.getId())
                .orElseThrow(() -> new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_NOT_FOUND));
        if (failedJob.getStatus() != AnalysisJobStatus.FAILED) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_STATUS_CONFLICT);
        }
        if (hasActiveBatchWideAnalysisJob(failedJob.getBatch())) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_ALREADY_IN_PROGRESS);
        }

        List<Episode> retryEpisodes = findRetryEpisodes(failedJob);
        if (retryEpisodes.isEmpty()) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_TARGET_NOT_FOUND);
        }
        assertNoActiveJobOfDifferentType(failedJob, retryEpisodes);

        return retryEpisodes.stream()
                .map(episode -> getOrCreateRetryJob(work, failedJob, episode))
                .map(this::toResponse)
                .toList();
    }

    private AnalysisJobResponse toResponse(AnalysisJob analysisJob) {
        List<UploadFile> uploadFiles = getUploadFiles(analysisJob);
        return analysisJobMapper.toResponse(
                analysisJob,
                uploadFiles,
                getTargetEpisodes(analysisJob)
        );
    }

    private List<Episode> getTargetEpisodes(AnalysisJob analysisJob) {
        if (!analysisJob.getTargetEpisodes().isEmpty()) {
            return List.copyOf(analysisJob.getTargetEpisodes());
        }
        return analysisJob.getEpisode() == null
                ? List.of()
                : List.of(analysisJob.getEpisode());
    }

    private List<Episode> findCurrentBatchEpisodes(List<UploadFile> uploadFiles) {
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
        return analysisJobRepository.existsByBatchIdAndEpisodeIsNullAndStatusIn(batch.getId(), activeStatuses)
                || analysisJobRepository.existsByEpisodeIdAndBatchIdAndStatusIn(
                episode.getId(), batch.getId(), activeStatuses);
    }

    private boolean hasActiveBatchWideAnalysisJob(UploadBatch batch) {
        Set<AnalysisJobStatus> activeStatuses = Set.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.RUNNING);
        return batch != null && analysisJobRepository.existsByBatchIdAndEpisodeIsNullAndStatusIn(
                batch.getId(), activeStatuses);
    }

    private List<Episode> findRetryEpisodes(AnalysisJob failedJob) {
        if (failedJob.getEpisode() != null) {
            return failedJob.getEpisode().getStatus() == EpisodeStatus.ARCHIVED
                    ? List.of()
                    : List.of(failedJob.getEpisode());
        }
        return getTargetEpisodes(failedJob).stream()
                .filter(episode -> episode.getStatus() == EpisodeStatus.FAILED)
                .toList();
    }

    private void assertNoActiveJobOfDifferentType(AnalysisJob failedJob, List<Episode> retryEpisodes) {
        Set<AnalysisJobStatus> activeStatuses = Set.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.RUNNING);
        boolean differentTypeJobIsActive = retryEpisodes.stream().anyMatch(episode ->
                analysisJobRepository.existsByEpisodeIdAndBatchIdAndJobTypeNotAndStatusIn(
                        episode.getId(),
                        failedJob.getBatch().getId(),
                        failedJob.getJobType(),
                        activeStatuses
                ));
        if (differentTypeJobIsActive) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_ALREADY_IN_PROGRESS);
        }
    }

    private AnalysisJob getOrCreateRetryJob(Work work, AnalysisJob failedJob, Episode episode) {
        Set<AnalysisJobStatus> activeStatuses = Set.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.RUNNING);
        return analysisJobRepository
                .findFirstByEpisodeIdAndBatchIdAndJobTypeAndStatusInOrderByCreatedAtDesc(
                        episode.getId(),
                        failedJob.getBatch().getId(),
                        failedJob.getJobType(),
                        activeStatuses
                )
                .orElseGet(() -> {
                    deleteSupersededPendingCandidates(
                            work.getId(),
                            failedJob.getBatch().getId(),
                            failedJob.getJobType(),
                            List.of(episode)
                    );
                    return analysisJobRepository.save(AnalysisJob.create(
                            work,
                            failedJob.getBatch(),
                            episode,
                            failedJob.getJobType()
                    ));
                });
    }

    /**
     * 새 분석 시도가 같은 목적·회차의 이전 미검토 후보를 대체하도록 pending 데이터만 정리한다.
     * 이미 확정·무시한 검토 이력과 다른 분석 목적의 후보는 보존한다.
     */
    private void deleteSupersededPendingCandidates(
            UUID workId,
            UUID batchId,
            AnalysisJobType jobType,
            List<Episode> episodes
    ) {
        List<UUID> episodeIds = episodes.stream()
                .map(Episode::getId)
                .toList();
        if (episodeIds.isEmpty()) {
            return;
        }
        settingCandidateRepository.deleteAllByAnalysisTargetAndReviewStatus(
                workId,
                batchId,
                episodeIds,
                jobType,
                SettingCandidateReviewStatus.PENDING_REVIEW
        );
    }

    private List<UploadFile> getUploadFiles(AnalysisJob analysisJob) {
        return getUploadFiles(analysisJob.getBatch());
    }

    private List<UploadFile> getUploadFiles(UploadBatch batch) {
        if (batch == null) {
            return List.of();
        }
        return uploadFileRepository.findAllByBatchIdOrderByCreatedAtAsc(batch.getId());
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

    private AnalysisBatchSummaryResponse toBatchSummary(
            AnalysisBatchPageRow pageRow,
            List<AnalysisJob> jobs,
            SettingCandidateBatchReviewCounts candidateCounts
    ) {
        Map<AnalysisJobType, List<AnalysisJob>> currentJobsByType = findCurrentJobsByType(jobs);
        List<AnalysisBatchJobGroupResponse> jobGroups = currentJobsByType.entrySet().stream()
                .map(entry -> analysisBatchMapper.toJobGroupResponse(
                        entry.getKey(),
                        resolveJobGroupStatus(entry.getValue()),
                        entry.getValue()
                ))
                .toList();
        Map<UUID, Episode> targetEpisodesById = currentJobsByType.values().stream()
                .flatMap(List::stream)
                .flatMap(job -> getTargetEpisodes(job).stream())
                .collect(Collectors.toMap(
                        Episode::getId,
                        episode -> episode,
                        (first, ignored) -> first
                ));
        Integer episodeStartNo = targetEpisodesById.values().stream()
                .map(Episode::getEpisodeNo)
                .min(Integer::compareTo)
                .orElse(null);
        Integer episodeEndNo = targetEpisodesById.values().stream()
                .map(Episode::getEpisodeNo)
                .max(Integer::compareTo)
                .orElse(null);
        long pendingCandidateCount = candidateCounts == null
                ? 0
                : candidateCounts.getPendingCandidateCount();
        LocalDateTime lastActivityAt = currentJobsByType.values().stream()
                .flatMap(List::stream)
                .map(AnalysisJob::getUpdatedAt)
                .max(Comparator.naturalOrder())
                .orElse(pageRow.getLastRequestedAt());

        return analysisBatchMapper.toResponse(
                pageRow,
                jobs.getFirst().getBatch(),
                resolveBatchStatus(jobGroups, pendingCandidateCount),
                episodeStartNo,
                episodeEndNo,
                targetEpisodesById.size(),
                candidateCounts,
                jobGroups,
                lastActivityAt
        );
    }

    /**
     * 재시도 이력까지 포함된 작업에서 분석 목적·회차별 최신 작업만 현재 상태로 선택한다.
     * episode_id가 없는 과거 작업은 보존된 targetEpisodes를 같은 기준으로 펼쳐서 처리한다.
     */
    private Map<AnalysisJobType, List<AnalysisJob>> findCurrentJobsByType(List<AnalysisJob> jobs) {
        Map<AnalysisJobType, LinkedHashMap<UUID, AnalysisJob>> jobsByEpisode = new EnumMap<>(
                AnalysisJobType.class
        );
        Map<AnalysisJobType, AnalysisJob> targetlessJobs = new EnumMap<>(AnalysisJobType.class);

        for (AnalysisJob job : jobs) {
            List<Episode> targetEpisodes = getTargetEpisodes(job);
            if (targetEpisodes.isEmpty()) {
                targetlessJobs.putIfAbsent(job.getJobType(), job);
                continue;
            }
            LinkedHashMap<UUID, AnalysisJob> latestJobs = jobsByEpisode.computeIfAbsent(
                    job.getJobType(),
                    ignored -> new LinkedHashMap<>()
            );
            targetEpisodes.forEach(episode -> latestJobs.putIfAbsent(episode.getId(), job));
        }

        Map<AnalysisJobType, List<AnalysisJob>> result = new EnumMap<>(AnalysisJobType.class);
        for (AnalysisJobType jobType : AnalysisJobType.values()) {
            LinkedHashMap<UUID, AnalysisJob> latestJobs = jobsByEpisode.get(jobType);
            if (latestJobs != null && !latestJobs.isEmpty()) {
                result.put(jobType, new ArrayList<>(latestJobs.values()));
                continue;
            }
            AnalysisJob targetlessJob = targetlessJobs.get(jobType);
            if (targetlessJob != null) {
                result.put(jobType, List.of(targetlessJob));
            }
        }
        return result;
    }

    private AnalysisBatchStatus resolveJobGroupStatus(List<AnalysisJob> currentJobs) {
        long failedCount = currentJobs.stream()
                .filter(job -> job.getStatus() == AnalysisJobStatus.FAILED)
                .count();
        if (currentJobs.stream().anyMatch(job ->
                job.getStatus() == AnalysisJobStatus.PENDING
                        || job.getStatus() == AnalysisJobStatus.RUNNING)) {
            return AnalysisBatchStatus.IN_PROGRESS;
        }
        if (failedCount == currentJobs.size()) {
            return AnalysisBatchStatus.FAILED;
        }
        if (failedCount > 0) {
            return AnalysisBatchStatus.PARTIALLY_FAILED;
        }
        return AnalysisBatchStatus.COMPLETED;
    }

    private AnalysisBatchStatus resolveBatchStatus(
            List<AnalysisBatchJobGroupResponse> jobGroups,
            long pendingCandidateCount
    ) {
        if (jobGroups.stream().anyMatch(group -> group.status() == AnalysisBatchStatus.IN_PROGRESS)) {
            return AnalysisBatchStatus.IN_PROGRESS;
        }
        if (jobGroups.stream().allMatch(group -> group.status() == AnalysisBatchStatus.FAILED)) {
            return AnalysisBatchStatus.FAILED;
        }
        if (jobGroups.stream().anyMatch(group ->
                group.status() == AnalysisBatchStatus.FAILED
                        || group.status() == AnalysisBatchStatus.PARTIALLY_FAILED)) {
            return AnalysisBatchStatus.PARTIALLY_FAILED;
        }
        if (pendingCandidateCount > 0) {
            return AnalysisBatchStatus.REVIEW_REQUIRED;
        }
        return AnalysisBatchStatus.COMPLETED;
    }
}
