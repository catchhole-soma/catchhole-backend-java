package org.monitoring.catchholebackend.domain.analysis.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobCompleteRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobFailRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobProgressRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobHeartbeatResponse;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobPayload;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisJobWorkerMapper;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisJobWorkerServiceImpl implements AnalysisJobWorkerService {

    private static final int CLAIM_SIZE = 1;
    private static final int EXPIRED_LEASE_SCAN_SIZE = 20;
    private static final int MAX_CLAIM_ATTEMPTS = 3;
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
    private static final String LEASE_EXPIRED_MESSAGE = "AI Worker lease가 반복 만료되어 작업을 종료했습니다.";
    private static final String NO_TARGET_EPISODES_MESSAGE = "분석 대상 회차가 없습니다.";
    private static final String INVALID_TARGET_EPISODE_COUNT_MESSAGE =
            "분석 작업은 정확히 한 회차를 대상으로 해야 합니다.";

    private final AnalysisJobRepository analysisJobRepository;
    private final AnalysisJobLeaseService analysisJobLeaseService;
    private final WorkCharacterRepository workCharacterRepository;
    private final CharacterSettingSchemaRepository characterSettingSchemaRepository;
    private final AnalysisJobWorkerMapper analysisJobWorkerMapper;
    private final AiTokenService aiTokenService;
    private final WorldSettingCandidateRepository worldSettingCandidateRepository;
    private final SettingCandidateRepository settingCandidateRepository;

    @Override
    @Transactional
    public Optional<WorkerAnalysisJobPayload> claimAnalysisJob(WorkerAnalysisJobClaimRequest request) {
        LocalDateTime now = LocalDateTime.now();
        recoverExpiredLeases(request.allowedJobTypes(), now);
        List<AnalysisJob> claimCandidates = analysisJobRepository.findClaimCandidates(
                AnalysisJobStatus.PENDING,
                request.allowedJobTypes(),
                PageRequest.of(0, CLAIM_SIZE)
        );
        if (claimCandidates.isEmpty()) {
            return Optional.empty();
        }

        AnalysisJob analysisJob = claimCandidates.getFirst();
        analysisJob.claim(request.modelName(), request.currentStep(), now.plus(LEASE_DURATION));

        boolean hiddenComparison = isHiddenComparisonJob(analysisJob);
        List<Episode> targetEpisodes = findTargetEpisodes(analysisJob);
        if (!hiddenComparison && targetEpisodes.isEmpty()) {
            analysisJob.fail(NO_TARGET_EPISODES_MESSAGE);
            return Optional.empty();
        }
        if (targetEpisodes.size() > 1) {
            analysisJob.fail(INVALID_TARGET_EPISODE_COUNT_MESSAGE);
            return Optional.empty();
        }
        Episode targetEpisode = targetEpisodes.isEmpty() ? null : targetEpisodes.getFirst();
        if (!hiddenComparison) {
            targetEpisode.markChunking();
        }

        UUID workId = analysisJob.getWork().getId();
        // canonical key·alias·value type 계약은 Job 종류와 무관하게 동일해야 한다.
        // 비교 전용 Worker도 claim payload만으로 같은 스키마를 해석할 수 있도록 항상 제공한다.
        List<CharacterSettingSchema> characterSettingSchemas =
                characterSettingSchemaRepository.findAllActiveForWork(workId);
        List<WorkCharacter> knownCharacters = hiddenComparison
                ? List.of()
                : workCharacterRepository.findAllByWorkIdAndStatusOrderByCreatedAtDesc(
                        workId,
                        CharacterStatus.ACTIVE
                );
        return Optional.of(analysisJobWorkerMapper.toResponse(
                analysisJob,
                targetEpisode,
                characterSettingSchemas,
                knownCharacters
        ));
    }

    @Override
    @Transactional
    public WorkerAnalysisJobHeartbeatResponse heartbeatAnalysisJob(UUID analysisJobId, UUID leaseToken) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        LocalDateTime leaseExpiresAt = LocalDateTime.now().plus(LEASE_DURATION);
        analysisJob.renewLease(leaseExpiresAt);
        return new WorkerAnalysisJobHeartbeatResponse(leaseToken, leaseExpiresAt);
    }

    @Override
    @Transactional
    public void updateProgress(UUID analysisJobId, UUID leaseToken, WorkerAnalysisJobProgressRequest request) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        analysisJob.updateCurrentStep(request.currentStep());
        analysisJob.updateCheckpointStage(request.checkpointStage());
        if (isHiddenComparisonJob(analysisJob)) {
            return;
        }
        if (request.episodeStatus() == null) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_EPISODE_STATUS_REQUIRED);
        }
        findTargetEpisodes(analysisJob).forEach(episode -> episode.updateStatus(request.episodeStatus()));
    }

    @Override
    @Transactional
    public void completeAnalysisJob(
            UUID analysisJobId,
            UUID leaseToken,
            WorkerAnalysisJobCompleteRequest request
    ) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        validateCompletion(analysisJob);
        if (!isHiddenComparisonJob(analysisJob)) {
            findTargetEpisodes(analysisJob).forEach(Episode::markAnalyzed);
        }
        long[] totals = aiTokenService.getAnalysisJobTokenTotals(analysisJobId);
        analysisJob.succeed(request.summaryJson(), Math.toIntExact(totals[0]), Math.toIntExact(totals[1]));
    }

    @Override
    @Transactional
    public void failAnalysisJob(UUID analysisJobId, UUID leaseToken, WorkerAnalysisJobFailRequest request) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        failProcessingWorldCandidates(analysisJob);
        failProcessingCharacterCandidates(analysisJob);
        if (!isHiddenComparisonJob(analysisJob)) {
            markTargetEpisodesFailed(analysisJob);
        }
        long[] totals = aiTokenService.getAnalysisJobTokenTotals(analysisJobId);
        analysisJob.fail(request.errorMessage(), Math.toIntExact(totals[0]), Math.toIntExact(totals[1]));
    }

    private void validateCompletion(AnalysisJob analysisJob) {
        if (analysisJob.getJobType() == AnalysisJobType.SETTING_EXTRACTION) {
            boolean characterComparisonIsRunning = settingCandidateRepository
                    .existsByAnalysisJobIdAndComparisonStatusIn(
                            analysisJob.getId(),
                            List.of(
                                    CharacterFactComparisonStatus.PENDING,
                                    CharacterFactComparisonStatus.PROCESSING
                            )
                    );
            boolean comparisonIsRunning = worldSettingCandidateRepository
                    .existsByAnalysisJobIdAndComparisonStatusIn(
                            analysisJob.getId(),
                            List.of(
                                    WorldSettingComparisonStatus.PENDING,
                                    WorldSettingComparisonStatus.PROCESSING
                            )
                    );
            // Java가 AI보다 먼저 배포되는 짧은 구간의 구버전 AI는 character 비교 checkpoint를
            // 보고하지 않는다. 실제 대기/처리 후보가 없다면 legacy 작업으로 보고 완료를 허용한다.
            if (characterComparisonIsRunning
                    || !analysisJob.hasReachedCheckpoint(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED)
                    || comparisonIsRunning) {
                throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_CHECKPOINT_INCOMPLETE);
            }
            return;
        }
        if (analysisJob.getJobType() == AnalysisJobType.WORLD_SETTING_COMPARISON
                && (analysisJob.getWorldSettingCandidate() == null
                || analysisJob.getWorldSettingCandidate().getComparisonStatus()
                != WorldSettingComparisonStatus.COMPLETED)) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_CHECKPOINT_INCOMPLETE);
        }
        if (analysisJob.getJobType() == AnalysisJobType.CHARACTER_FACT_COMPARISON) {
            SettingCandidate candidate = analysisJob.getSettingCandidate();
            if (candidate == null) {
                throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_CHECKPOINT_INCOMPLETE);
            }
            // claim 전에 사용자가 후보를 확정/무시했다면 hidden job은 할 일이 없는 정상 종료다.
            if (candidate.getReviewStatus() != SettingCandidateReviewStatus.PENDING_REVIEW) {
                return;
            }
            // 후보 매칭 변경이나 dismiss가 hidden Job보다 먼저 반영되면 이 Job은 obsolete다.
            // 처리할 비교가 없는 정상 no-op으로 끝내고 분석 실패 이력을 만들지 않는다.
            if (candidate.getComparisonStatus() == CharacterFactComparisonStatus.WAITING_FOR_CHARACTER_MATCH
                    || candidate.getComparisonStatus() == CharacterFactComparisonStatus.NOT_REQUIRED) {
                return;
            }
            if (candidate.getComparisonStatus() != CharacterFactComparisonStatus.COMPLETED) {
                throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_CHECKPOINT_INCOMPLETE);
            }
        }
    }

    private void recoverExpiredLeases(
            Collection<AnalysisJobType> jobTypes,
            LocalDateTime now
    ) {
        List<AnalysisJob> expiredJobs = analysisJobRepository.findExpiredLeaseCandidates(
                AnalysisJobStatus.RUNNING,
                jobTypes,
                now,
                PageRequest.of(0, EXPIRED_LEASE_SCAN_SIZE)
        );
        for (AnalysisJob expiredJob : expiredJobs) {
            aiTokenService.releaseReservedForAnalysisJob(
                    expiredJob.getId(),
                    AiTokenUsageOutcome.WORKER_LEASE_EXPIRED
            );
            if (expiredJob.getClaimAttemptCount() >= MAX_CLAIM_ATTEMPTS) {
                failProcessingWorldCandidates(expiredJob);
                failProcessingCharacterCandidates(expiredJob);
                if (!isHiddenComparisonJob(expiredJob)) {
                    markTargetEpisodesFailed(expiredJob);
                }
                long[] totals = aiTokenService.getAnalysisJobTokenTotals(expiredJob.getId());
                expiredJob.fail(
                        LEASE_EXPIRED_MESSAGE,
                        Math.toIntExact(totals[0]),
                        Math.toIntExact(totals[1])
                );
                continue;
            }
            recoverProcessingWorldCandidates(expiredJob);
            recoverProcessingCharacterCandidates(expiredJob);
            expiredJob.requeueExpiredLease();
        }
    }

    private void recoverProcessingWorldCandidates(AnalysisJob analysisJob) {
        if (analysisJob.getJobType() == AnalysisJobType.SETTING_EXTRACTION) {
            worldSettingCandidateRepository.findAllByAnalysisJobIdAndComparisonStatus(
                    analysisJob.getId(),
                    WorldSettingComparisonStatus.PROCESSING
            ).forEach(WorldSettingCandidate::recoverExpiredComparison);
            return;
        }
        if (analysisJob.getJobType() == AnalysisJobType.WORLD_SETTING_COMPARISON
                && analysisJob.getWorldSettingCandidate() != null) {
            analysisJob.getWorldSettingCandidate().recoverExpiredComparison();
        }
    }

    private void failProcessingWorldCandidates(AnalysisJob analysisJob) {
        if (analysisJob.getJobType() == AnalysisJobType.SETTING_EXTRACTION) {
            worldSettingCandidateRepository.findAllByAnalysisJobIdAndComparisonStatus(
                    analysisJob.getId(),
                    WorldSettingComparisonStatus.PROCESSING
            ).forEach(candidate -> candidate.failComparison(LEASE_EXPIRED_MESSAGE));
            return;
        }
        if (analysisJob.getJobType() == AnalysisJobType.WORLD_SETTING_COMPARISON
                && analysisJob.getWorldSettingCandidate() != null
                && analysisJob.getWorldSettingCandidate().getComparisonStatus()
                == WorldSettingComparisonStatus.PROCESSING) {
            analysisJob.getWorldSettingCandidate().failComparison(LEASE_EXPIRED_MESSAGE);
        }
    }

    private void recoverProcessingCharacterCandidates(AnalysisJob analysisJob) {
        if (analysisJob.getJobType() == AnalysisJobType.SETTING_EXTRACTION) {
            settingCandidateRepository.findAllByAnalysisJobIdAndComparisonStatus(
                    analysisJob.getId(),
                    CharacterFactComparisonStatus.PROCESSING
            ).forEach(SettingCandidate::recoverExpiredComparison);
            return;
        }
        if (analysisJob.getJobType() == AnalysisJobType.CHARACTER_FACT_COMPARISON
                && analysisJob.getSettingCandidate() != null) {
            analysisJob.getSettingCandidate().recoverExpiredComparison();
        }
    }

    private void failProcessingCharacterCandidates(AnalysisJob analysisJob) {
        if (analysisJob.getJobType() == AnalysisJobType.SETTING_EXTRACTION) {
            settingCandidateRepository.findAllByAnalysisJobIdAndComparisonStatusIn(
                    analysisJob.getId(),
                    List.of(
                            CharacterFactComparisonStatus.PENDING,
                            CharacterFactComparisonStatus.PROCESSING
                    )
            ).forEach(candidate -> candidate.failComparison(LEASE_EXPIRED_MESSAGE));
            return;
        }
        if (analysisJob.getJobType() == AnalysisJobType.CHARACTER_FACT_COMPARISON
                && analysisJob.getSettingCandidate() != null
                && (analysisJob.getSettingCandidate().getComparisonStatus()
                == CharacterFactComparisonStatus.PENDING
                || analysisJob.getSettingCandidate().getComparisonStatus()
                == CharacterFactComparisonStatus.PROCESSING)) {
            analysisJob.getSettingCandidate().failComparison(LEASE_EXPIRED_MESSAGE);
        }
    }

    private boolean isHiddenComparisonJob(AnalysisJob analysisJob) {
        return analysisJob.getJobType() == AnalysisJobType.WORLD_SETTING_COMPARISON
                || analysisJob.getJobType() == AnalysisJobType.CHARACTER_FACT_COMPARISON;
    }

    private void markTargetEpisodesFailed(AnalysisJob analysisJob) {
        findTargetEpisodes(analysisJob).stream()
                .filter(episode -> episode.getStatus()
                        != EpisodeStatus.ANALYZED)
                .forEach(Episode::markFailed);
    }

    private List<Episode> findTargetEpisodes(AnalysisJob analysisJob) {
        return List.copyOf(analysisJob.getTargetEpisodes());
    }

}
