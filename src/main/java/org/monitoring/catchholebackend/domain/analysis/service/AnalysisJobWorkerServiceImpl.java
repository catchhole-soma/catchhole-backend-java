package org.monitoring.catchholebackend.domain.analysis.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobCompleteRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobFailRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobProgressRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobPayload;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisJobWorkerMapper;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileRole;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisJobWorkerServiceImpl implements AnalysisJobWorkerService {

    private static final int CLAIM_SIZE = 1;
    private static final String NO_TARGET_EPISODES_MESSAGE = "분석 대상 회차가 없습니다.";

    private final AnalysisJobRepository analysisJobRepository;
    private final UploadFileRepository uploadFileRepository;
    private final EpisodeRepository episodeRepository;
    private final WorkCharacterRepository workCharacterRepository;
    private final CharacterSettingSchemaRepository characterSettingSchemaRepository;
    private final AnalysisJobWorkerMapper analysisJobWorkerMapper;

    @Override
    @Transactional
    public Optional<WorkerAnalysisJobPayload> claimAnalysisJob(WorkerAnalysisJobClaimRequest request) {
        List<AnalysisJob> claimCandidates = analysisJobRepository.findClaimCandidates(
                AnalysisJobStatus.PENDING,
                PageRequest.of(0, CLAIM_SIZE)
        );
        if (claimCandidates.isEmpty()) {
            return Optional.empty();
        }

        AnalysisJob analysisJob = claimCandidates.getFirst();
        analysisJob.start(resolveModelName(request), resolveCurrentStep(request));

        List<Episode> targetEpisodes = findTargetEpisodes(analysisJob);
        if (targetEpisodes.isEmpty()) {
            analysisJob.fail(NO_TARGET_EPISODES_MESSAGE);
            return Optional.empty();
        }
        targetEpisodes.forEach(Episode::markChunking);

        UUID workId = analysisJob.getWork().getId();
        List<CharacterSettingSchema> characterSettingSchemas =
                characterSettingSchemaRepository.findAllActiveForWork(workId);
        List<WorkCharacter> knownCharacters = workCharacterRepository.findAllByWorkIdOrderByCreatedAtDesc(workId);
        return Optional.of(analysisJobWorkerMapper.toPayload(
                analysisJob,
                targetEpisodes,
                characterSettingSchemas,
                knownCharacters
        ));
    }

    @Override
    @Transactional
    public void updateProgress(UUID analysisJobId, WorkerAnalysisJobProgressRequest request) {
        AnalysisJob analysisJob = getRunningJob(analysisJobId);
        analysisJob.updateCurrentStep(request.currentStep());
        updateEpisodeProgress(findTargetEpisodes(analysisJob), request.currentStep());
    }

    @Override
    @Transactional
    public void completeAnalysisJob(UUID analysisJobId, WorkerAnalysisJobCompleteRequest request) {
        AnalysisJob analysisJob = getRunningJob(analysisJobId);
        findTargetEpisodes(analysisJob).forEach(Episode::markAnalyzed);
        analysisJob.succeed(request.summaryJson(), request.inputTokenCount(), request.outputTokenCount());
    }

    @Override
    @Transactional
    public void failAnalysisJob(UUID analysisJobId, WorkerAnalysisJobFailRequest request) {
        AnalysisJob analysisJob = getRunningJob(analysisJobId);
        findTargetEpisodes(analysisJob).stream()
                .filter(episode -> episode.getStatus() != org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.ANALYZED)
                .forEach(Episode::markFailed);
        analysisJob.fail(request.errorMessage());
    }

    private AnalysisJob getRunningJob(UUID analysisJobId) {
        AnalysisJob analysisJob = analysisJobRepository.findById(analysisJobId)
                .orElseThrow(() -> new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_NOT_FOUND));
        if (analysisJob.getStatus() != AnalysisJobStatus.RUNNING) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_STATUS_CONFLICT);
        }
        return analysisJob;
    }

    /**
     * 작업에 연결된 업로드 배치에서 회차 파일만 골라 분석 대상 회차 목록을 만든다.
     * 배치가 없거나 회차 파일이 없으면 워커에 넘길 대상이 없으므로 빈 목록을 반환한다.
     */
    private List<Episode> findTargetEpisodes(AnalysisJob analysisJob) {
        if (analysisJob.getEpisode() != null) {
            return List.of(analysisJob.getEpisode());
        }
        UploadBatch batch = analysisJob.getBatch();
        if (batch == null) {
            return List.of();
        }

        List<UUID> episodeUploadFileIds = uploadFileRepository.findAllByBatchIdAndFileRole(
                        batch.getId(),
                        UploadFileRole.EPISODE
                )
                .stream()
                .map(UploadFile::getId)
                .toList();
        if (episodeUploadFileIds.isEmpty()) {
            return List.of();
        }
        return episodeRepository.findAllBySourceFileIdInAndStatusNotOrderByEpisodeNoAsc(
                episodeUploadFileIds,
                org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.ARCHIVED
        );
    }

    private void updateEpisodeProgress(List<Episode> episodes, String currentStep) {
        if (currentStep == null) {
            return;
        }
        String normalizedStep = currentStep.toUpperCase();
        if (normalizedStep.contains("PREPROCESSED") || currentStep.contains("전처리 완료")) {
            episodes.forEach(Episode::markPreprocessed);
        } else if (normalizedStep.contains("PREPROCESS") || currentStep.contains("전처리")) {
            episodes.forEach(Episode::markPreprocessing);
        } else if (normalizedStep.contains("CHUNKED") || currentStep.contains("청크 저장")) {
            episodes.forEach(Episode::markChunked);
        } else if (normalizedStep.contains("CHUNK") || currentStep.contains("청킹")) {
            episodes.forEach(Episode::markChunking);
        } else if (normalizedStep.contains("ANALYZ") || currentStep.contains("분석") || currentStep.contains("추출")) {
            episodes.forEach(Episode::markAnalyzing);
        }
    }

    private String resolveModelName(WorkerAnalysisJobClaimRequest request) {
        return request == null ? null : request.modelName();
    }

    private String resolveCurrentStep(WorkerAnalysisJobClaimRequest request) {
        return request == null ? null : request.currentStep();
    }
}
