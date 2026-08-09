package org.monitoring.catchholebackend.domain.worldsetting.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobLeaseService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonCompleteRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonContextRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonFailRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingCandidatePayload;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingComparisonContextResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingSubjectPageResponse;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.exception.WorldSettingErrorCode;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingWorkerMapper;
import org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingNameNormalizer;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorldSettingWorkerServiceImpl implements WorldSettingWorkerService {

    private static final int CLAIM_SIZE = 1;

    private final AnalysisJobLeaseService analysisJobLeaseService;
    private final WorldSettingCandidateRepository worldSettingCandidateRepository;
    private final WorldSettingRepository worldSettingRepository;
    private final WorldSettingWorkerMapper worldSettingWorkerMapper;

    @Override
    @Transactional
    public List<WorkerWorldSettingCandidatePayload> publishWorldSettingCandidates(
            UUID analysisJobId,
            UUID leaseToken,
            WorkerWorldSettingCandidatePublishRequest request
    ) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        if (analysisJob.getJobType() != AnalysisJobType.SETTING_EXTRACTION
                || analysisJob.getEpisode() == null) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_WORKER_JOB_INVALID);
        }
        if (!analysisJob.hasReachedCheckpoint(
                AnalysisJobCheckpointStage.CHARACTER_CANDIDATES_SAVED
        )) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_CHECKPOINT_INCOMPLETE);
        }
        if (analysisJob.hasReachedCheckpoint(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED)) {
            return worldSettingWorkerMapper.toResponseList(
                    worldSettingCandidateRepository
                            .findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(analysisJobId)
            );
        }
        if (worldSettingCandidateRepository.existsByAnalysisJobIdAndReviewStatusNot(
                analysisJobId,
                WorldSettingReviewStatus.PENDING_REVIEW
        )) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_NOT_EDITABLE);
        }
        validateDistinctCandidateSettingNames(request);

        worldSettingCandidateRepository.deleteAllByAnalysisJobId(analysisJobId);
        List<WorldSettingCandidate> candidates = request.candidates().stream()
                .map(item -> worldSettingWorkerMapper.toEntity(analysisJob, item))
                .toList();
        List<WorldSettingCandidate> savedCandidates = worldSettingCandidateRepository
                .saveAll(candidates);
        worldSettingCandidateRepository.flush();
        analysisJob.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
        return worldSettingWorkerMapper.toResponseList(savedCandidates);
    }

    private void validateDistinctCandidateSettingNames(
            WorkerWorldSettingCandidatePublishRequest request
    ) {
        Set<String> candidateKeys = new HashSet<>();
        for (WorkerWorldSettingCandidatePublishRequest.Candidate candidate : request.candidates()) {
            String candidateKey = candidate.category().name()
                    + "|" + WorldSettingNameNormalizer.duplicateKey(candidate.subjectName())
                    + "|" + WorldSettingNameNormalizer.duplicateKey(candidate.settingName());
            if (!candidateKeys.add(candidateKey)) {
                throw new AppException(
                        WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_SETTING_NAME_DUPLICATED
                );
            }
        }
    }

    @Override
    @Transactional
    public Optional<WorkerWorldSettingCandidatePayload> claimNextWorldSettingComparison(
            UUID analysisJobId,
            UUID leaseToken
    ) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        WorldSettingCandidate candidate;
        if (analysisJob.getJobType() == AnalysisJobType.SETTING_EXTRACTION) {
            if (!analysisJob.hasReachedCheckpoint(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED)) {
                throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_CHECKPOINT_INCOMPLETE);
            }
            candidate = worldSettingCandidateRepository.findComparisonClaimCandidates(
                    analysisJobId,
                    WorldSettingReviewStatus.PENDING_REVIEW,
                    WorldSettingComparisonStatus.PENDING,
                    PageRequest.of(0, CLAIM_SIZE)
            ).stream().findFirst().orElse(null);
        } else if (analysisJob.getJobType() == AnalysisJobType.WORLD_SETTING_COMPARISON) {
            candidate = lockLinkedCandidate(analysisJob);
            if (candidate.getComparisonStatus() != WorldSettingComparisonStatus.PENDING) {
                return Optional.empty();
            }
        } else {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_WORKER_JOB_INVALID);
        }
        if (candidate == null) {
            return Optional.empty();
        }
        candidate.startComparison();
        return Optional.of(worldSettingWorkerMapper.toResponse(candidate));
    }

    @Override
    @Transactional
    public WorkerWorldSettingSubjectPageResponse getWorldSettingSubjects(
            UUID analysisJobId,
            UUID leaseToken,
            WorldSettingCategory category,
            int page,
            int size
    ) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        Page<WorldSetting> worldSettingPage = worldSettingRepository
                .findAllByWorkIdAndCategoryOrderBySubjectNameAscIdAsc(
                        analysisJob.getWork().getId(),
                        category,
                        PageRequest.of(page, size)
                );
        return new WorkerWorldSettingSubjectPageResponse(
                worldSettingPage.getContent().stream()
                        .map(worldSettingWorkerMapper::toSubjectResponse)
                        .toList(),
                page,
                worldSettingPage.hasNext()
        );
    }

    @Override
    @Transactional
    public WorkerWorldSettingComparisonContextResponse getWorldSettingComparisonContext(
            UUID analysisJobId,
            UUID candidateId,
            UUID leaseToken,
            WorkerWorldSettingComparisonContextRequest request
    ) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        WorldSettingCandidate candidate = getOwnedProcessingCandidate(analysisJob, candidateId);
        Set<UUID> requestedIds = new HashSet<>(request.targetWorldSettingIds());
        if (requestedIds.size() != request.targetWorldSettingIds().size()) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_COMPARISON_TARGET_INVALID);
        }
        List<WorldSetting> targets = requestedIds.isEmpty()
                ? List.of()
                : worldSettingRepository.findAllComparisonTargets(
                        candidate.getWork().getId(),
                        candidate.getCategory(),
                        requestedIds
                );
        if (targets.size() != requestedIds.size()) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_COMPARISON_TARGET_INVALID);
        }
        UUID exactTargetId = findExactTarget(candidate).map(WorldSetting::getId).orElse(null);
        return new WorkerWorldSettingComparisonContextResponse(
                worldSettingWorkerMapper.toResponse(candidate),
                exactTargetId,
                targets.stream()
                        .map(worldSettingWorkerMapper::toComparisonTargetResponse)
                        .toList()
        );
    }

    @Override
    @Transactional
    public void completeWorldSettingComparison(
            UUID analysisJobId,
            UUID candidateId,
            UUID leaseToken,
            WorkerWorldSettingComparisonCompleteRequest request
    ) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        WorldSettingCandidate candidate = getOwnedProcessingCandidate(analysisJob, candidateId);
        Map<UUID, WorldSetting> contextTargets = validateContext(candidate, request);
        WorldSetting target = request.targetWorldSettingId() == null
                ? null
                : contextTargets.get(request.targetWorldSettingId());
        if (request.targetWorldSettingId() != null && target == null) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_COMPARISON_TARGET_INVALID);
        }
        validateProposal(target, request);
        String beforeValue = target == null || isBlank(request.matchedPropertyName())
                ? null
                : target.getPropertyValue(request.matchedPropertyName());
        candidate.completeComparison(
                target,
                request.consolidationStatus(),
                request.suggestedOperation(),
                request.proposedSettingName(),
                beforeValue,
                request.proposedValue(),
                request.comparisonReason(),
                worldSettingWorkerMapper.toJsonNode(request.rawComparisonJson()),
                LocalDateTime.now()
        );
    }

    @Override
    @Transactional
    public void failWorldSettingComparison(
            UUID analysisJobId,
            UUID candidateId,
            UUID leaseToken,
            WorkerWorldSettingComparisonFailRequest request
    ) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        getOwnedProcessingCandidate(analysisJob, candidateId).failComparison(request.errorMessage());
    }

    private Map<UUID, WorldSetting> validateContext(
            WorldSettingCandidate candidate,
            WorkerWorldSettingComparisonCompleteRequest request
    ) {
        Set<UUID> contextTargetIds = new HashSet<>();
        Map<UUID, Long> expectedVersions = new HashMap<>();
        for (WorkerWorldSettingComparisonCompleteRequest.ContextVersion contextVersion
                : request.contextVersions()) {
            if (!contextTargetIds.add(contextVersion.worldSettingId())) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_COMPARISON_TARGET_INVALID);
            }
            expectedVersions.put(contextVersion.worldSettingId(), contextVersion.version());
        }
        List<WorldSetting> contextTargets = contextTargetIds.isEmpty()
                ? List.of()
                : worldSettingRepository.findAllComparisonTargets(
                        candidate.getWork().getId(),
                        candidate.getCategory(),
                        contextTargetIds
                );
        if (contextTargets.size() != contextTargetIds.size()
                || contextTargets.stream().anyMatch(target ->
                target.getVersion() != expectedVersions.get(target.getId()))) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_CONTEXT_STALE);
        }
        UUID currentExactTargetId = findExactTarget(candidate).map(WorldSetting::getId).orElse(null);
        if (!Objects.equals(currentExactTargetId, request.exactTargetWorldSettingId())) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_CONTEXT_STALE);
        }
        if (currentExactTargetId != null && !contextTargetIds.contains(currentExactTargetId)) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_COMPARISON_TARGET_INVALID);
        }
        Map<UUID, WorldSetting> contextTargetsById = new HashMap<>();
        contextTargets.forEach(target -> contextTargetsById.put(target.getId(), target));
        return contextTargetsById;
    }

    private void validateProposal(
            WorldSetting target,
            WorkerWorldSettingComparisonCompleteRequest request
    ) {
        WorldSettingOperation operation = request.suggestedOperation();
        if (operation == WorldSettingOperation.UPDATE || operation == WorldSettingOperation.MERGE) {
            if (target == null || isBlank(request.matchedPropertyName())) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_COMPARISON_TARGET_INVALID);
            }
            String storedName = target.getStoredPropertyName(request.matchedPropertyName());
            if (storedName == null || !storedName.equals(request.proposedSettingName())) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_COMPARISON_TARGET_INVALID);
            }
            return;
        }
        if (operation == WorldSettingOperation.ADD) {
            if (!isBlank(request.matchedPropertyName())
                    || target != null && target.hasProperty(request.proposedSettingName())) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_COMPARISON_TARGET_INVALID);
            }
            return;
        }
        if (operation == WorldSettingOperation.EXCLUDE && !isBlank(request.matchedPropertyName())) {
            if (target == null || target.getStoredPropertyName(request.matchedPropertyName()) == null) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_COMPARISON_TARGET_INVALID);
            }
        }
    }

    private Optional<WorldSetting> findExactTarget(WorldSettingCandidate candidate) {
        return worldSettingRepository.findByWorkIdAndCategoryAndNormalizedSubjectName(
                candidate.getWork().getId(),
                candidate.getCategory(),
                WorldSettingNameNormalizer.duplicateKey(candidate.getSubjectName())
        );
    }

    private WorldSettingCandidate getOwnedProcessingCandidate(AnalysisJob analysisJob, UUID candidateId) {
        WorldSettingCandidate candidate = worldSettingCandidateRepository
                .findByIdAndWorkIdForUpdate(candidateId, analysisJob.getWork().getId())
                .orElseThrow(() -> new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_NOT_FOUND));
        validateCandidateOwnership(analysisJob, candidate);
        if (candidate.getComparisonStatus() != WorldSettingComparisonStatus.PROCESSING) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
        }
        return candidate;
    }

    private WorldSettingCandidate lockLinkedCandidate(AnalysisJob analysisJob) {
        if (analysisJob.getWorldSettingCandidate() == null) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_WORKER_JOB_INVALID);
        }
        WorldSettingCandidate candidate = worldSettingCandidateRepository
                .findByIdAndWorkIdForUpdate(
                        analysisJob.getWorldSettingCandidate().getId(),
                        analysisJob.getWork().getId()
                )
                .orElseThrow(() -> new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_NOT_FOUND));
        validateCandidateOwnership(analysisJob, candidate);
        return candidate;
    }

    private void validateCandidateOwnership(AnalysisJob analysisJob, WorldSettingCandidate candidate) {
        boolean owned = analysisJob.getJobType() == AnalysisJobType.SETTING_EXTRACTION
                ? candidate.getAnalysisJob().getId().equals(analysisJob.getId())
                : analysisJob.getJobType() == AnalysisJobType.WORLD_SETTING_COMPARISON
                && analysisJob.getWorldSettingCandidate() != null
                && analysisJob.getWorldSettingCandidate().getId().equals(candidate.getId());
        if (!owned) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_WORKER_JOB_INVALID);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
