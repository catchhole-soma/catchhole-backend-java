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
        findTargetEpisodes(analysisJob)
                .forEach(episode -> episode.updateStatus(request.episodeStatus()));
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

    private List<Episode> findTargetEpisodes(AnalysisJob analysisJob) {
        return List.copyOf(analysisJob.getTargetEpisodes());
    }

    private String resolveModelName(WorkerAnalysisJobClaimRequest request) {
        return request == null ? null : request.modelName();
    }

    private String resolveCurrentStep(WorkerAnalysisJobClaimRequest request) {
        return request == null ? null : request.currentStep();
    }
}
