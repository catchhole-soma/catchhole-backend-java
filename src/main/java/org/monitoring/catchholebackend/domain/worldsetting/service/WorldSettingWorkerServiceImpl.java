package org.monitoring.catchholebackend.domain.worldsetting.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobLeaseService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchCompleteRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchContextRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonCompleteRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonContextRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonFailRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingCandidatePayload;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingComparisonBatchContextResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingComparisonBatchPayload;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingComparisonContextResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingSubjectPageResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingSubjectResolutionPendingResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingSubjectResolutionResponse;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonBatch;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecision;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecision.ExistingRootPropertyMoveSnapshot;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecisionSource;
import org.monitoring.catchholebackend.domain.worldsetting.exception.WorldSettingErrorCode;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingWorkerMapper;
import org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingNameNormalizer;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingComparisonBatchRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingComparisonDecisionRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingComparisonDecisionSourceRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonBatchStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonReviewReason;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonValidationReason;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingConsolidationStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSubjectResolutionType;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class WorldSettingWorkerServiceImpl implements WorldSettingWorkerService {

    private static final int CLAIM_SIZE = 1;
    private static final int MAX_BATCH_CANDIDATES = 20;
    private static final int MAX_BATCH_INPUT_CHARACTERS = 30_000;
    private static final String STALE_SUBJECT_RESOLUTION_RESET_MESSAGE =
            "canonical 주체 해소 대상이 변경되어 다시 해소해야 합니다.";

    private final AnalysisJobLeaseService analysisJobLeaseService;
    private final WorldSettingCandidateRepository worldSettingCandidateRepository;
    private final WorldSettingComparisonBatchRepository comparisonBatchRepository;
    private final WorldSettingComparisonDecisionRepository comparisonDecisionRepository;
    private final WorldSettingComparisonDecisionSourceRepository comparisonSourceRepository;
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
                    + "|" + Objects.toString(
                            WorldSettingNameNormalizer.duplicateKey(candidate.scopeName()),
                            "<root>"
                    )
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
            if (!candidate.isPendingReview()
                    || candidate.getComparisonStatus() != WorldSettingComparisonStatus.PENDING) {
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
    public Optional<WorkerWorldSettingComparisonBatchPayload>
            claimNextWorldSettingComparisonBatch(
            UUID analysisJobId,
            UUID leaseToken
    ) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        if (analysisJob.getJobType() == AnalysisJobType.WORLD_SETTING_COMPARISON) {
            WorldSettingCandidate candidate = lockLinkedCandidate(analysisJob);
            if (!candidate.isPendingReview()
                    || candidate.getComparisonStatus() != WorldSettingComparisonStatus.PENDING) {
                return Optional.empty();
            }
            requireCurrentSubjectResolution(candidate);
            List<WorldSettingCandidate> candidates = List.of(candidate);
            WorldSettingComparisonBatch batch = startComparisonBatch(
                    analysisJob,
                    candidates
            );
            return Optional.of(worldSettingWorkerMapper.toComparisonBatchResponse(
                    batch,
                    candidates
            ));
        }
        if (analysisJob.getJobType() != AnalysisJobType.SETTING_EXTRACTION) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_WORKER_JOB_INVALID);
        }
        if (!analysisJob.hasReachedCheckpoint(
                AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED
        )) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_CHECKPOINT_INCOMPLETE);
        }

        while (true) {
            WorldSettingCandidate first = worldSettingCandidateRepository
                    .findComparisonClaimCandidates(
                            analysisJobId,
                            WorldSettingReviewStatus.PENDING_REVIEW,
                            WorldSettingComparisonStatus.PENDING,
                            PageRequest.of(0, CLAIM_SIZE)
                    ).stream()
                    .findFirst()
                    .orElse(null);
            if (first == null) {
                return Optional.empty();
            }
            requireCurrentSubjectResolution(first);
            List<WorldSettingCandidate> candidates = orderedGroupCandidates(
                    worldSettingCandidateRepository.findComparisonBatchCandidatesForUpdate(
                            analysisJobId,
                            first.getSourceEpisode().getId(),
                            first.getCategory(),
                            first.getCanonicalSubjectKey(),
                            WorldSettingReviewStatus.PENDING_REVIEW,
                            WorldSettingComparisonStatus.PENDING
                    ),
                    first.getScopeName()
            );
            if (candidates.isEmpty()) {
                throw new AppException(
                        WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT
                );
            }
            if (exceedsComparisonBatchLimit(candidates)) {
                completeOverflowComparisonBatch(analysisJob, candidates);
                continue;
            }
            WorldSettingComparisonBatch batch = startComparisonBatch(
                    analysisJob,
                    candidates
            );
            return Optional.of(worldSettingWorkerMapper.toComparisonBatchResponse(
                    batch,
                    candidates
            ));
        }
    }

    @Override
    @Transactional
    public WorkerWorldSettingSubjectResolutionPendingResponse
            getPendingWorldSettingSubjectResolutions(
            UUID analysisJobId,
            UUID leaseToken
    ) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        List<WorldSettingCandidate> candidates = subjectResolutionCandidates(analysisJob);
        return worldSettingWorkerMapper.toSubjectResolutionPendingResponse(
                candidates.stream()
                        .filter(candidate -> !hasCurrentSubjectResolution(candidate))
                        .toList()
        );
    }

    @Override
    @Transactional
    public WorkerWorldSettingSubjectResolutionResponse resolveWorldSettingSubjects(
            UUID analysisJobId,
            UUID leaseToken,
            WorkerWorldSettingSubjectResolutionRequest request
    ) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        List<WorldSettingCandidate> candidates = subjectResolutionCandidates(analysisJob);
        Map<UUID, WorldSettingCandidate> candidatesById = new LinkedHashMap<>();
        candidates.forEach(candidate -> candidatesById.put(candidate.getId(), candidate));

        Map<UUID, WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput> requestsById =
                new LinkedHashMap<>();
        for (WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput resolution
                : request.resolutions()) {
            if (requestsById.put(resolution.candidateId(), resolution) != null
                    || !candidatesById.containsKey(resolution.candidateId())) {
                throw new AppException(
                        WorldSettingErrorCode.WORLD_SETTING_SUBJECT_RESOLUTION_INVALID
                );
            }
        }
        boolean missesRequiredCandidate = candidates.stream()
                .filter(candidate -> !hasCurrentSubjectResolution(candidate))
                .anyMatch(candidate -> !requestsById.containsKey(candidate.getId()));
        if (missesRequiredCandidate) {
            throw new AppException(
                    WorldSettingErrorCode.WORLD_SETTING_SUBJECT_RESOLUTION_INVALID
            );
        }

        Map<UUID, SubjectResolution> proposedResolutions = new LinkedHashMap<>();
        for (WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput requestItem
                : request.resolutions()) {
            WorldSettingCandidate candidate = candidatesById.get(requestItem.candidateId());
            SubjectResolution resolution = resolveSubject(analysisJob, candidate, requestItem);
            proposedResolutions.put(candidate.getId(), resolution);
        }
        Map<String, String> newCanonicalNames = new HashMap<>();
        for (WorldSettingCandidate candidate : candidates) {
            SubjectResolution proposed = proposedResolutions.get(candidate.getId());
            if (proposed != null && proposed.type() == WorldSettingSubjectResolutionType.NEW) {
                newCanonicalNames.merge(
                        proposed.canonicalKey(),
                        candidate.getSubjectName(),
                        this::deterministicDisplayName
                );
            } else if (candidate.getSubjectResolutionType()
                    == WorldSettingSubjectResolutionType.NEW) {
                newCanonicalNames.merge(
                        candidate.getCanonicalSubjectKey(),
                        candidate.getCanonicalSubjectName(),
                        this::deterministicDisplayName
                );
            }
        }
        for (WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput requestItem
                : request.resolutions()) {
            WorldSettingCandidate candidate = candidatesById.get(requestItem.candidateId());
            SubjectResolution resolution = proposedResolutions.get(candidate.getId());
            if (resolution.type() == WorldSettingSubjectResolutionType.NEW) {
                resolution = new SubjectResolution(
                        resolution.type(),
                        resolution.canonicalKey(),
                        newCanonicalNames.get(resolution.canonicalKey()),
                        resolution.targetIds()
                );
            }
            if (hasCurrentSubjectResolution(candidate)
                    && !sameSubjectResolution(candidate, resolution)) {
                throw new AppException(
                        WorldSettingErrorCode.WORLD_SETTING_SUBJECT_RESOLUTION_INVALID
                );
            }
            candidate.resolveSubject(
                    resolution.type(),
                    resolution.canonicalKey(),
                    resolution.canonicalName(),
                    worldSettingWorkerMapper.toJsonNode(resolution.targetIds())
            );
        }
        worldSettingCandidateRepository.flush();
        return worldSettingWorkerMapper.toSubjectResolutionResponse(candidates);
    }

    private String deterministicDisplayName(String left, String right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    @Override
    @Transactional
    public WorkerWorldSettingComparisonBatchContextResponse
            getWorldSettingComparisonBatchContext(
            UUID analysisJobId,
            UUID comparisonBatchId,
            UUID leaseToken,
            WorkerWorldSettingComparisonBatchContextRequest request
    ) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        WorldSettingComparisonBatch batch = getOwnedComparisonBatchForUpdate(
                analysisJob,
                comparisonBatchId
        );
        requireProcessingBatch(batch);
        List<WorldSettingCandidate> candidates = getProcessingBatchCandidates(batch);
        candidates.forEach(this::requireCurrentSubjectResolution);

        Set<UUID> requestedIds = new HashSet<>(request.targetWorldSettingIds());
        if (requestedIds.size() != request.targetWorldSettingIds().size()) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.CONTEXT_TARGET_DUPLICATED
            );
        }
        Set<UUID> resolvedTargetIds = new HashSet<>(
                worldSettingWorkerMapper.toUuidList(
                        batch.getResolvedTargetWorldSettingIds()
                )
        );
        if (!requestedIds.equals(resolvedTargetIds)) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.BATCH_RESOLVED_TARGET_COVERAGE_INVALID
            );
        }
        List<WorldSetting> targets = requestedIds.isEmpty()
                ? List.of()
                : worldSettingRepository.findAllComparisonTargets(
                        batch.getWork().getId(),
                        batch.getCategory(),
                        requestedIds
                ).stream()
                        .sorted(Comparator.comparing(WorldSetting::getId))
                        .toList();
        if (targets.size() != requestedIds.size()) {
            throw new AppException(
                    WorldSettingErrorCode.WORLD_SETTING_SUBJECT_RESOLUTION_STALE
            );
        }

        List<WorkerWorldSettingComparisonBatchContextResponse.ExactTarget> exactTargets =
                candidates.stream()
                        .map(candidate -> new WorkerWorldSettingComparisonBatchContextResponse.ExactTarget(
                                candidate.getComparisonCandidateRef(),
                                findExactTarget(candidate).map(WorldSetting::getId).orElse(null)
                        ))
                        .toList();
        for (WorkerWorldSettingComparisonBatchContextResponse.ExactTarget exactTarget
                : exactTargets) {
            if (exactTarget.worldSettingId() != null
                    && !requestedIds.contains(exactTarget.worldSettingId())) {
                throw invalidComparisonTarget(
                        WorldSettingComparisonValidationReason.EXACT_TARGET_NOT_IN_CONTEXT
                );
            }
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("targetVersions", targets.stream()
                .map(target -> Map.of(
                        "worldSettingId",
                        target.getId().toString(),
                        "version",
                        target.getVersion()
                ))
                .toList());
        snapshot.put("exactTargets", exactTargets.stream()
                .map(exactTarget -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("candidateRef", exactTarget.candidateRef());
                    item.put(
                            "worldSettingId",
                            exactTarget.worldSettingId() == null
                                    ? null
                                    : exactTarget.worldSettingId().toString()
                    );
                    return item;
                })
                .toList());
        batch.recordContext(worldSettingWorkerMapper.toJsonNode(snapshot));

        return new WorkerWorldSettingComparisonBatchContextResponse(
                batch.getId(),
                worldSettingWorkerMapper.toComparisonBatchCandidates(candidates),
                exactTargets,
                targets.stream()
                        .map(worldSettingWorkerMapper::toComparisonTargetResponse)
                        .toList()
        );
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
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.CONTEXT_TARGET_DUPLICATED
            );
        }
        List<WorldSetting> targets = requestedIds.isEmpty()
                ? List.of()
                : worldSettingRepository.findAllComparisonTargets(
                        candidate.getWork().getId(),
                        candidate.getCategory(),
                        requestedIds
                ).stream()
                        .sorted(Comparator.comparing(WorldSetting::getId))
                        .toList();
        if (targets.size() != requestedIds.size()) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.CONTEXT_TARGET_NOT_FOUND
            );
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
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.SELECTED_TARGET_NOT_IN_CONTEXT
            );
        }
        WorldSetting exactTarget = request.exactTargetWorldSettingId() == null
                ? null
                : contextTargets.get(request.exactTargetWorldSettingId());
        validateProposal(candidate, target, exactTarget, request);
        String beforeValue = target == null || isBlank(request.matchedPropertyName())
                ? null
                : target.getPropertyValue(request.matchedScopeName(), request.matchedPropertyName());
        candidate.completeComparison(
                target,
                request.consolidationStatus(),
                request.suggestedOperation(),
                request.matchedScopeName(),
                request.matchedPropertyName(),
                request.comparisonReviewReason(),
                request.proposedScopeName(),
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
    public void completeWorldSettingComparisonBatch(
            UUID analysisJobId,
            UUID comparisonBatchId,
            UUID leaseToken,
            WorkerWorldSettingComparisonBatchCompleteRequest request
    ) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        String requestHash = completionHash(request);
        WorldSettingComparisonBatch batch = getOwnedComparisonBatchForUpdate(
                analysisJob,
                comparisonBatchId
        );
        if (batch.isCompletedWith(requestHash)) {
            return;
        }
        if (!batch.isProcessing()) {
            throw new AppException(
                    batch.getStatus() == WorldSettingComparisonBatchStatus.COMPLETED
                            ? WorldSettingErrorCode.WORLD_SETTING_COMPARISON_BATCH_COMPLETION_CONFLICT
                            : WorldSettingErrorCode.WORLD_SETTING_COMPARISON_BATCH_STATUS_CONFLICT
            );
        }

        List<WorldSettingCandidate> candidates = getProcessingBatchCandidates(batch);
        candidates.forEach(this::requireCurrentSubjectResolution);
        Map<UUID, WorldSetting> contextTargets = validateBatchContext(
                batch,
                candidates,
                request
        );
        Map<WorkerWorldSettingComparisonBatchCompleteRequest.Decision, List<WorldSettingCandidate>>
                sourcesByDecision = validateBatchSourceCoverage(candidates, request.decisions());

        List<WorldSettingComparisonDecision> decisions = new ArrayList<>();
        Map<String, WorldSettingComparisonDecision> decisionsByRef = new LinkedHashMap<>();
        Set<String> requestedRootMoveKeys = new HashSet<>();
        for (Map.Entry<WorkerWorldSettingComparisonBatchCompleteRequest.Decision,
                List<WorldSettingCandidate>> entry : sourcesByDecision.entrySet()) {
            WorkerWorldSettingComparisonBatchCompleteRequest.Decision decisionRequest =
                    entry.getKey();
            WorldSetting target = decisionRequest.targetWorldSettingId() == null
                    ? null
                    : contextTargets.get(decisionRequest.targetWorldSettingId());
            if (decisionRequest.targetWorldSettingId() != null && target == null) {
                throw invalidComparisonTarget(
                        WorldSettingComparisonValidationReason.SELECTED_TARGET_NOT_IN_CONTEXT
                );
            }
            validateBatchDecision(batch, entry.getValue(), target, decisionRequest);
            List<ExistingRootPropertyMoveSnapshot> rootMoveSnapshots =
                    rootPropertyMoveSnapshots(target, decisionRequest);
            for (ExistingRootPropertyMoveSnapshot snapshot : rootMoveSnapshots) {
                String moveKey = target.getId()
                        + "|"
                        + WorldSettingNameNormalizer.duplicateKey(snapshot.settingName());
                if (!requestedRootMoveKeys.add(moveKey)) {
                    throw invalidComparisonTarget(
                            WorldSettingComparisonValidationReason.ROOT_PROPERTY_MOVE_DUPLICATED
                    );
                }
            }
            String beforeValue = target == null || isBlank(decisionRequest.matchedPropertyName())
                    ? null
                    : target.getPropertyValue(
                            decisionRequest.matchedScopeName(),
                            decisionRequest.matchedPropertyName()
                    );
            WorldSettingComparisonDecision decision = WorldSettingComparisonDecision.create(
                    batch,
                    decisionRequest.decisionRef(),
                    decisionRequest.canonicalSubjectName(),
                    target,
                    decisionRequest.matchedScopeName(),
                    decisionRequest.matchedPropertyName(),
                    decisionRequest.consolidationStatus(),
                    decisionRequest.suggestedOperation(),
                    decisionRequest.comparisonReviewReason(),
                    decisionRequest.proposedScopeName(),
                    decisionRequest.proposedSettingName(),
                    beforeValue,
                    decisionRequest.proposedValue(),
                    decisionRequest.comparisonReason(),
                    rootMoveSnapshots,
                    worldSettingWorkerMapper.toJsonNode(decisionRequest.rawComparisonJson())
            );
            decisions.add(decision);
            decisionsByRef.put(decisionRequest.decisionRef(), decision);
        }
        validateBatchDecisionInteractions(
                request.decisions(),
                requestedRootMoveKeys
        );
        validateSyntheticScopeChildCount(
                batch,
                List.copyOf(sourcesByDecision.keySet()),
                contextTargets
        );
        comparisonDecisionRepository.saveAllAndFlush(decisions);

        LocalDateTime comparedAt = LocalDateTime.now();
        List<WorldSettingComparisonDecisionSource> sources = new ArrayList<>();
        for (Map.Entry<WorkerWorldSettingComparisonBatchCompleteRequest.Decision,
                List<WorldSettingCandidate>> entry : sourcesByDecision.entrySet()) {
            WorldSettingComparisonDecision decision = decisionsByRef.get(
                    entry.getKey().decisionRef()
            );
            List<WorldSettingCandidate> sourceCandidates = entry.getValue();
            for (int index = 0; index < sourceCandidates.size(); index++) {
                WorldSettingCandidate candidate = sourceCandidates.get(index);
                candidate.completeComparison(decision, comparedAt);
                sources.add(WorldSettingComparisonDecisionSource.create(
                        batch,
                        decision,
                        candidate,
                        candidate.getComparisonCandidateRef(),
                        index
                ));
            }
        }
        comparisonSourceRepository.saveAll(sources);
        worldSettingCandidateRepository.flush();
        batch.complete(
                requestHash,
                worldSettingWorkerMapper.toJsonNode(request.rawComparisonJson())
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
        WorldSettingCandidate candidate = getOwnedCandidateForUpdate(
                analysisJob,
                candidateId
        );
        if (candidate.getComparisonStatus() == WorldSettingComparisonStatus.PENDING) {
            NormalizedComparisonFailure failure = normalizeComparisonFailure(request);
            if (failure.failureCode() != AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED) {
                throw new AppException(
                        WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT
                );
            }
            candidate.interruptComparisonForTokenQuota(failure.errorMessage());
            return;
        }
        if (candidate.getComparisonStatus() != WorldSettingComparisonStatus.PROCESSING) {
            throw new AppException(
                    WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT
            );
        }
        candidate.failComparison(
                request.failureCode(),
                request.errorMessage(),
                request.sourceErrorCode(),
                request.sourceReasonCode()
        );
    }

    @Override
    @Transactional
    public void failWorldSettingComparisonBatch(
            UUID analysisJobId,
            UUID comparisonBatchId,
            UUID leaseToken,
            WorkerWorldSettingComparisonFailRequest request
    ) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        NormalizedComparisonFailure failure = normalizeComparisonFailure(request);
        WorldSettingComparisonBatch batch = getOwnedComparisonBatchForUpdate(
                analysisJob,
                comparisonBatchId
        );
        if (!batch.isProcessing()) {
            if (batch.getStatus() == WorldSettingComparisonBatchStatus.FAILED
                    && isSameFailedBatchRequest(batch, failure)) {
                return;
            }
            throw new AppException(
                    WorldSettingErrorCode.WORLD_SETTING_COMPARISON_BATCH_STATUS_CONFLICT
            );
        }
        List<WorldSettingCandidate> candidates = getProcessingBatchCandidates(batch);
        for (WorldSettingCandidate candidate : candidates) {
            candidate.failComparison(
                    failure.failureCode(),
                    failure.errorMessage(),
                    failure.sourceErrorCode(),
                    failure.sourceReasonCode()
            );
        }
        batch.fail(failure.failureCode(), failure.errorMessage());
    }

    @Override
    @Transactional
    public void resetStaleWorldSettingSubjectResolution(
            UUID analysisJobId,
            UUID comparisonBatchId,
            UUID leaseToken
    ) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        WorldSettingComparisonBatch batch = getOwnedComparisonBatchForUpdate(
                analysisJob,
                comparisonBatchId
        );
        if (batch.getStatus() == WorldSettingComparisonBatchStatus.FAILED
                && batch.getFailureCode() == AnalysisFailureCode.COMPARISON_VALIDATION_FAILED
                && Objects.equals(
                batch.getErrorMessage(),
                STALE_SUBJECT_RESOLUTION_RESET_MESSAGE
        )) {
            return;
        }
        requireProcessingBatch(batch);
        List<WorldSettingCandidate> candidates = getProcessingBatchCandidates(batch);
        if (candidates.stream().allMatch(this::hasCurrentSubjectResolution)) {
            throw new AppException(
                    WorldSettingErrorCode.WORLD_SETTING_COMPARISON_BATCH_STATUS_CONFLICT
            );
        }
        batch.fail(
                AnalysisFailureCode.COMPARISON_VALIDATION_FAILED,
                STALE_SUBJECT_RESOLUTION_RESET_MESSAGE
        );
        candidates.forEach(WorldSettingCandidate::resetStaleSubjectResolution);
        worldSettingCandidateRepository.flush();
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
                throw invalidComparisonTarget(
                        WorldSettingComparisonValidationReason.CONTEXT_VERSION_DUPLICATED
                );
            }
            expectedVersions.put(contextVersion.worldSettingId(), contextVersion.version());
        }
        List<WorldSetting> contextTargets = contextTargetIds.isEmpty()
                ? List.of()
                : worldSettingRepository.findAllComparisonTargetsForUpdate(
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
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.EXACT_TARGET_NOT_IN_CONTEXT
            );
        }
        Map<UUID, WorldSetting> contextTargetsById = new HashMap<>();
        contextTargets.forEach(target -> contextTargetsById.put(target.getId(), target));
        return contextTargetsById;
    }

    private void validateProposal(
            WorldSettingCandidate candidate,
            WorldSetting target,
            WorldSetting exactTarget,
            WorkerWorldSettingComparisonCompleteRequest request
    ) {
        WorldSettingSuggestedOperation operation = request.suggestedOperation();
        if (operation == WorldSettingSuggestedOperation.REVIEW_REQUIRED) {
            validateScopeUnresolvedReview(candidate, target, request);
            return;
        }
        if (request.comparisonReviewReason() != null) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.REVIEW_REASON_FORBIDDEN
            );
        }
        if (operation == WorldSettingSuggestedOperation.UPDATE
                || operation == WorldSettingSuggestedOperation.MERGE) {
            if (target == null || isBlank(request.matchedPropertyName())) {
                throw invalidComparisonTarget(
                        WorldSettingComparisonValidationReason.MATCHED_TARGET_REQUIRED
                );
            }
            WorldSetting.StoredPropertyPath storedPath = target.getStoredPropertyPath(
                    request.matchedScopeName(),
                    request.matchedPropertyName()
            );
            if (storedPath == null
                    || !sameName(storedPath.scopeName(), candidate.getScopeName())
                    || !sameName(storedPath.scopeName(), request.proposedScopeName())
                    || !sameName(storedPath.settingName(), request.proposedSettingName())) {
                throw invalidComparisonTarget(
                        WorldSettingComparisonValidationReason.PROPOSED_PATH_MISMATCH
                );
            }
            return;
        }
        if (operation == WorldSettingSuggestedOperation.ADD) {
            if (!isBlank(request.matchedPropertyName())
                    || !isBlank(request.matchedScopeName())) {
                throw invalidComparisonTarget(
                        WorldSettingComparisonValidationReason.ADD_MATCHED_PATH_FORBIDDEN
                );
            }
            if (requiresScopeReviewForRootAdd(candidate, target, request)
                    || requiresScopeReviewForRootAdd(candidate, exactTarget, request)) {
                throw invalidComparisonTarget(
                        WorldSettingComparisonValidationReason.SCOPE_REVIEW_REQUIRED
                );
            }
            if (target != null && (target.hasProperty(
                            request.proposedScopeName(),
                            request.proposedSettingName()
                    ) || target.hasPathConflict(
                            request.proposedScopeName(),
                            request.proposedSettingName()
                    ))) {
                throw invalidComparisonTarget(
                        WorldSettingComparisonValidationReason.PROPOSED_PATH_CONFLICT
                );
            }
            return;
        }
        if (operation == WorldSettingSuggestedOperation.EXCLUDE
                && !isBlank(request.matchedPropertyName())) {
            WorldSetting.StoredPropertyPath storedPath = target == null
                    ? null
                    : target.getStoredPropertyPath(
                            request.matchedScopeName(),
                            request.matchedPropertyName()
                    );
            if (storedPath == null || !sameName(storedPath.scopeName(), candidate.getScopeName())) {
                throw invalidComparisonTarget(
                        WorldSettingComparisonValidationReason.EXCLUDE_MATCHED_PATH_INVALID
                );
            }
        } else if (operation == WorldSettingSuggestedOperation.EXCLUDE
                && !isBlank(request.matchedScopeName())) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.EXCLUDE_MATCHED_SCOPE_WITHOUT_PROPERTY
            );
        }
    }

    private boolean requiresScopeReviewForRootAdd(
            WorldSettingCandidate candidate,
            WorldSetting target,
            WorkerWorldSettingComparisonCompleteRequest request
    ) {
        return target != null
                && candidate.getScopeName() == null
                && isBlank(request.proposedScopeName())
                && sameName(request.proposedSettingName(), candidate.getSettingName())
                && target.getProperties().stream().anyMatch(property ->
                property.scopeName() != null
                        && sameName(property.settingName(), candidate.getSettingName()));
    }

    private void validateScopeUnresolvedReview(
            WorldSettingCandidate candidate,
            WorldSetting target,
            WorkerWorldSettingComparisonCompleteRequest request
    ) {
        if (request.comparisonReviewReason() != WorldSettingComparisonReviewReason.SCOPE_UNRESOLVED) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.SCOPE_REVIEW_REASON_INVALID
            );
        }
        if (target == null) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.SCOPE_REVIEW_TARGET_REQUIRED
            );
        }
        if (candidate.getScopeName() != null) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.SCOPE_REVIEW_CANDIDATE_ALREADY_SCOPED
            );
        }
        if (isBlank(request.matchedScopeName()) || isBlank(request.matchedPropertyName())) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.SCOPE_REVIEW_MATCHED_PATH_REQUIRED
            );
        }
        if (target.hasProperty(candidate.getScopeName(), candidate.getSettingName())) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.SCOPE_REVIEW_ROOT_PATH_EXISTS
            );
        }
        if (!sameName(request.proposedScopeName(), candidate.getScopeName())
                || !sameName(request.proposedSettingName(), candidate.getSettingName())) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.PROPOSED_PATH_MISMATCH
            );
        }
        WorldSetting.StoredPropertyPath storedPath = target.getStoredPropertyPath(
                request.matchedScopeName(),
                request.matchedPropertyName()
        );
        if (storedPath == null
                || storedPath.scopeName() == null
                || !sameName(storedPath.settingName(), candidate.getSettingName())) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.SCOPE_REVIEW_MATCHED_PATH_INVALID
            );
        }
    }

    private List<WorldSettingCandidate> subjectResolutionCandidates(
            AnalysisJob analysisJob
    ) {
        if (analysisJob.getJobType() == AnalysisJobType.SETTING_EXTRACTION) {
            if (analysisJob.getEpisode() == null
                    || !analysisJob.hasReachedCheckpoint(
                    AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED
            )) {
                throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_CHECKPOINT_INCOMPLETE);
            }
            return worldSettingCandidateRepository.findSubjectResolutionCandidatesForUpdate(
                    analysisJob.getId(),
                    WorldSettingReviewStatus.PENDING_REVIEW,
                    WorldSettingComparisonStatus.PENDING
            );
        }
        if (analysisJob.getJobType() == AnalysisJobType.WORLD_SETTING_COMPARISON) {
            WorldSettingCandidate candidate = lockLinkedCandidate(analysisJob);
            if (candidate.isPendingReview()
                    && candidate.getComparisonStatus() == WorldSettingComparisonStatus.PENDING) {
                return List.of(candidate);
            }
            return List.of();
        }
        throw new AppException(WorldSettingErrorCode.WORLD_SETTING_WORKER_JOB_INVALID);
    }

    private SubjectResolution resolveSubject(
            AnalysisJob analysisJob,
            WorldSettingCandidate candidate,
            WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput request
    ) {
        List<UUID> targetIds = request.targetWorldSettingIds().stream()
                .distinct()
                .sorted()
                .toList();
        if (targetIds.size() != request.targetWorldSettingIds().size()) {
            throw new AppException(
                    WorldSettingErrorCode.WORLD_SETTING_SUBJECT_RESOLUTION_INVALID
            );
        }
        List<WorldSetting> targets = targetIds.isEmpty()
                ? List.of()
                : worldSettingRepository.findAllComparisonTargetsForUpdate(
                        analysisJob.getWork().getId(),
                        candidate.getCategory(),
                        targetIds
                );
        if (targets.size() != targetIds.size()) {
            throw new AppException(
                    WorldSettingErrorCode.WORLD_SETTING_SUBJECT_RESOLUTION_INVALID
            );
        }
        UUID exactTargetId = findExactTarget(candidate)
                .map(WorldSetting::getId)
                .orElse(null);
        if (exactTargetId != null && !targetIds.contains(exactTargetId)) {
            throw new AppException(
                    WorldSettingErrorCode.WORLD_SETTING_SUBJECT_RESOLUTION_INVALID
            );
        }
        if (targetIds.isEmpty()) {
            return new SubjectResolution(
                    WorldSettingSubjectResolutionType.NEW,
                    "NEW:" + WorldSettingNameNormalizer.duplicateKey(candidate.getSubjectName()),
                    candidate.getSubjectName(),
                    targetIds
            );
        }
        if (targetIds.size() == 1) {
            WorldSetting target = targets.getFirst();
            return new SubjectResolution(
                    WorldSettingSubjectResolutionType.EXISTING,
                    "TARGET:" + target.getId(),
                    target.getSubjectName(),
                    targetIds
            );
        }
        return new SubjectResolution(
                WorldSettingSubjectResolutionType.AMBIGUOUS,
                "AMBIGUOUS:" + candidate.getId(),
                candidate.getSubjectName(),
                targetIds
        );
    }

    private boolean hasCurrentSubjectResolution(WorldSettingCandidate candidate) {
        if (!candidate.hasSubjectResolution()) {
            return false;
        }
        List<UUID> targetIds;
        try {
            targetIds = worldSettingWorkerMapper.toUuidList(
                    candidate.getResolvedTargetWorldSettingIds()
            );
        } catch (RuntimeException exception) {
            return false;
        }
        if (targetIds.size() != new HashSet<>(targetIds).size()
                || !targetIds.equals(targetIds.stream().sorted().toList())) {
            return false;
        }
        List<WorldSetting> targets = targetIds.isEmpty()
                ? List.of()
                : worldSettingRepository.findAllComparisonTargetsForUpdate(
                        candidate.getWork().getId(),
                        candidate.getCategory(),
                        targetIds
                );
        if (targets.size() != targetIds.size()) {
            return false;
        }

        SubjectResolution expected;
        if (candidate.getSubjectResolutionType() == WorldSettingSubjectResolutionType.NEW) {
            if (!targetIds.isEmpty()
                    || findExactTarget(candidate).isPresent()
                    || !sameName(
                            candidate.getCanonicalSubjectName(),
                            candidate.getSubjectName()
                    )) {
                return false;
            }
            expected = new SubjectResolution(
                    WorldSettingSubjectResolutionType.NEW,
                    "NEW:" + WorldSettingNameNormalizer.duplicateKey(candidate.getSubjectName()),
                    candidate.getCanonicalSubjectName(),
                    targetIds
            );
        } else if (candidate.getSubjectResolutionType()
                == WorldSettingSubjectResolutionType.EXISTING) {
            if (targetIds.size() != 1) {
                return false;
            }
            WorldSetting target = targets.getFirst();
            expected = new SubjectResolution(
                    WorldSettingSubjectResolutionType.EXISTING,
                    "TARGET:" + target.getId(),
                    target.getSubjectName(),
                    targetIds
            );
        } else if (candidate.getSubjectResolutionType()
                == WorldSettingSubjectResolutionType.AMBIGUOUS) {
            if (targetIds.size() < 2) {
                return false;
            }
            expected = new SubjectResolution(
                    WorldSettingSubjectResolutionType.AMBIGUOUS,
                    "AMBIGUOUS:" + candidate.getId(),
                    candidate.getSubjectName(),
                    targetIds
            );
        } else {
            return false;
        }
        return sameSubjectResolution(candidate, expected);
    }

    private boolean sameSubjectResolution(
            WorldSettingCandidate candidate,
            SubjectResolution resolution
    ) {
        return candidate.getSubjectResolutionType() == resolution.type()
                && Objects.equals(candidate.getCanonicalSubjectKey(), resolution.canonicalKey())
                && Objects.equals(candidate.getCanonicalSubjectName(), resolution.canonicalName())
                && worldSettingWorkerMapper.toUuidList(
                candidate.getResolvedTargetWorldSettingIds()
        ).equals(resolution.targetIds());
    }

    private void requireCurrentSubjectResolution(WorldSettingCandidate candidate) {
        if (hasCurrentSubjectResolution(candidate)) {
            return;
        }
        throw new AppException(
                candidate.hasSubjectResolution()
                        ? WorldSettingErrorCode.WORLD_SETTING_SUBJECT_RESOLUTION_STALE
                        : WorldSettingErrorCode.WORLD_SETTING_SUBJECT_RESOLUTION_REQUIRED
        );
    }


    private List<WorldSettingCandidate> orderedGroupCandidates(
            List<WorldSettingCandidate> candidates,
            String rawScopeName
    ) {
        return candidates.stream()
                .filter(candidate -> sameName(candidate.getScopeName(), rawScopeName))
                .sorted(Comparator
                        .comparingInt(this::firstEvidenceStartOffset)
                        .thenComparing(
                                WorldSettingCandidate::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())
                        )
                        .thenComparing(WorldSettingCandidate::getId))
                .toList();
    }

    private int firstEvidenceStartOffset(WorldSettingCandidate candidate) {
        JsonNode evidenceSpans = candidate.getEvidenceSpans();
        if (evidenceSpans == null || !evidenceSpans.isArray()) {
            return Integer.MAX_VALUE;
        }
        int firstOffset = Integer.MAX_VALUE;
        for (JsonNode span : evidenceSpans) {
            JsonNode startOffset = span.get("startOffset");
            if (startOffset != null && startOffset.canConvertToInt()) {
                firstOffset = Math.min(firstOffset, startOffset.asInt());
            }
        }
        return firstOffset;
    }

    private boolean exceedsComparisonBatchLimit(List<WorldSettingCandidate> candidates) {
        return candidates.size() > MAX_BATCH_CANDIDATES
                || estimatedInputCharacters(candidates) > MAX_BATCH_INPUT_CHARACTERS;
    }

    private int estimatedInputCharacters(List<WorldSettingCandidate> candidates) {
        long total = 0;
        for (WorldSettingCandidate candidate : candidates) {
            total += characterCount(candidate.getSubjectName());
            total += characterCount(candidate.getScopeName());
            total += characterCount(candidate.getSettingName());
            total += characterCount(candidate.getExtractedValue());
            JsonNode evidenceSpans = candidate.getEvidenceSpans();
            if (evidenceSpans != null && evidenceSpans.isArray()) {
                for (JsonNode span : evidenceSpans) {
                    JsonNode quote = span.get("quote");
                    if (quote != null && quote.isTextual()) {
                        total += quote.asText().length();
                    }
                }
            }
            if (total > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) total;
    }

    private int characterCount(String value) {
        return value == null ? 0 : value.length();
    }

    private WorldSettingComparisonBatch startComparisonBatch(
            AnalysisJob analysisJob,
            List<WorldSettingCandidate> candidates
    ) {
        validateBatchCandidateGroup(analysisJob, candidates);
        WorldSettingCandidate first = candidates.getFirst();
        WorldSettingComparisonBatch batch = comparisonBatchRepository.saveAndFlush(
                WorldSettingComparisonBatch.create(
                        analysisJob.getWork(),
                        first.getSourceEpisode(),
                        analysisJob,
                        first.getCategory(),
                        first.getScopeName(),
                        first.getSubjectResolutionType(),
                        first.getCanonicalSubjectKey(),
                        first.getCanonicalSubjectName(),
                        first.getResolvedTargetWorldSettingIds(),
                        candidates.size()
                )
        );
        for (int index = 0; index < candidates.size(); index++) {
            candidates.get(index).startComparison(batch, "C" + (index + 1));
        }
        worldSettingCandidateRepository.flush();
        return batch;
    }

    private void validateBatchCandidateGroup(
            AnalysisJob analysisJob,
            List<WorldSettingCandidate> candidates
    ) {
        if (candidates.isEmpty()) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_SELECTION_INVALID);
        }
        WorldSettingCandidate first = candidates.getFirst();
        for (WorldSettingCandidate candidate : candidates) {
            validateCandidateOwnership(analysisJob, candidate);
            if (!candidate.isPendingReview()
                    || candidate.getComparisonStatus() != WorldSettingComparisonStatus.PENDING
                    || !candidate.getSourceEpisode().getId().equals(
                    first.getSourceEpisode().getId()
            )
                    || candidate.getCategory() != first.getCategory()
                    || !Objects.equals(
                    candidate.getCanonicalSubjectKey(),
                    first.getCanonicalSubjectKey()
            )
                    || candidate.getSubjectResolutionType()
                    != first.getSubjectResolutionType()
                    || !Objects.equals(
                    candidate.getCanonicalSubjectName(),
                    first.getCanonicalSubjectName()
            )
                    || !Objects.equals(
                    candidate.getResolvedTargetWorldSettingIds(),
                    first.getResolvedTargetWorldSettingIds()
            )
                    || !sameName(candidate.getScopeName(), first.getScopeName())) {
                throw new AppException(
                        WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT
                );
            }
            requireCurrentSubjectResolution(candidate);
        }
    }

    private void completeOverflowComparisonBatch(
            AnalysisJob analysisJob,
            List<WorldSettingCandidate> candidates
    ) {
        log.warn(
                "World-setting comparison batch held for review. "
                        + "clusterOverflowOrReviewRequiredCount=1 candidateCount={} "
                        + "estimatedInputCharacters={}",
                candidates.size(),
                estimatedInputCharacters(candidates)
        );
        WorldSettingComparisonBatch batch = startComparisonBatch(analysisJob, candidates);
        WorldSetting canonicalTarget = batch.getSubjectResolutionType()
                == WorldSettingSubjectResolutionType.EXISTING
                ? worldSettingRepository.findByIdAndWorkId(
                        worldSettingWorkerMapper.toUuidList(
                                batch.getResolvedTargetWorldSettingIds()
                        ).getFirst(),
                        batch.getWork().getId()
                ).orElseThrow(() -> new AppException(
                        WorldSettingErrorCode.WORLD_SETTING_SUBJECT_RESOLUTION_STALE
                ))
                : null;
        List<WorldSettingComparisonDecision> decisions = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            WorldSettingCandidate candidate = candidates.get(index);
            decisions.add(WorldSettingComparisonDecision.create(
                    batch,
                    "D" + (index + 1),
                    batch.getCanonicalSubjectName(),
                    canonicalTarget,
                    null,
                    null,
                    WorldSettingConsolidationStatus.SINGLE,
                    WorldSettingSuggestedOperation.REVIEW_REQUIRED,
                    WorldSettingComparisonReviewReason.BATCH_LIMIT_EXCEEDED,
                    candidate.getScopeName(),
                    candidate.getSettingName(),
                    null,
                    candidate.getExtractedValue(),
                    "같이 비교해야 할 후보가 묶음 상한을 넘어 자동 비교하지 않았습니다.",
                    worldSettingWorkerMapper.toJsonNode(Map.of(
                            "reviewReason",
                            WorldSettingComparisonReviewReason.BATCH_LIMIT_EXCEEDED.name()
                    ))
            ));
        }
        comparisonDecisionRepository.saveAllAndFlush(decisions);

        LocalDateTime comparedAt = LocalDateTime.now();
        List<WorldSettingComparisonDecisionSource> sources = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            WorldSettingCandidate candidate = candidates.get(index);
            WorldSettingComparisonDecision decision = decisions.get(index);
            candidate.completeComparison(decision, comparedAt);
            sources.add(WorldSettingComparisonDecisionSource.create(
                    batch,
                    decision,
                    candidate,
                    candidate.getComparisonCandidateRef(),
                    0
            ));
        }
        comparisonSourceRepository.saveAll(sources);
        worldSettingCandidateRepository.flush();
        batch.requireReview(worldSettingWorkerMapper.toJsonNode(Map.of(
                "candidateCount",
                candidates.size(),
                "estimatedInputCharacters",
                estimatedInputCharacters(candidates),
                "reviewReason",
                WorldSettingComparisonReviewReason.BATCH_LIMIT_EXCEEDED.name()
        )));
    }

    private WorldSettingComparisonBatch getOwnedComparisonBatchForUpdate(
            AnalysisJob analysisJob,
            UUID comparisonBatchId
    ) {
        WorldSettingComparisonBatch batch = comparisonBatchRepository
                .findByIdAndWorkIdForUpdate(comparisonBatchId, analysisJob.getWork().getId())
                .orElseThrow(() -> new AppException(
                        WorldSettingErrorCode.WORLD_SETTING_COMPARISON_BATCH_NOT_FOUND
                ));
        if (!batch.getAnalysisJob().getId().equals(analysisJob.getId())) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_WORKER_JOB_INVALID);
        }
        return batch;
    }

    private void requireProcessingBatch(WorldSettingComparisonBatch batch) {
        if (!batch.isProcessing()) {
            throw new AppException(
                    WorldSettingErrorCode.WORLD_SETTING_COMPARISON_BATCH_STATUS_CONFLICT
            );
        }
    }

    private List<WorldSettingCandidate> getProcessingBatchCandidates(
            WorldSettingComparisonBatch batch
    ) {
        List<WorldSettingCandidate> candidates = worldSettingCandidateRepository
                .findAllByComparisonBatchIdOrderByCreatedAtAscIdAsc(batch.getId())
                .stream()
                .sorted(Comparator.comparingInt(candidate -> candidateRefIndex(
                        candidate.getComparisonCandidateRef()
                )))
                .toList();
        if (candidates.size() != batch.getCandidateCount()
                || candidates.stream().anyMatch(candidate ->
                candidate.getComparisonStatus() != WorldSettingComparisonStatus.PROCESSING)) {
            throw new AppException(
                    WorldSettingErrorCode.WORLD_SETTING_COMPARISON_BATCH_STATUS_CONFLICT
            );
        }
        return candidates;
    }

    private boolean isSameFailedBatchRequest(
            WorldSettingComparisonBatch batch,
            NormalizedComparisonFailure failure
    ) {
        if (batch.getFailureCode() != failure.failureCode()
                || !Objects.equals(batch.getErrorMessage(), failure.errorMessage())) {
            return false;
        }
        List<WorldSettingCandidate> candidates = worldSettingCandidateRepository
                .findAllByComparisonBatchIdOrderByCreatedAtAscIdAsc(batch.getId());
        return candidates.size() == batch.getCandidateCount()
                && candidates.stream().allMatch(candidate ->
                candidate.getComparisonStatus() == WorldSettingComparisonStatus.FAILED
                        && candidate.getComparisonFailureCode() == failure.failureCode()
                        && Objects.equals(
                        candidate.getComparisonErrorMessage(),
                        failure.errorMessage()
                )
                        && Objects.equals(
                        candidate.getComparisonSourceErrorCode(),
                        failure.sourceErrorCode()
                )
                        && candidate.getComparisonSourceReasonCode()
                        == failure.sourceReasonCode());
    }

    private NormalizedComparisonFailure normalizeComparisonFailure(
            WorkerWorldSettingComparisonFailRequest request
    ) {
        AnalysisFailureCode failureCode = AnalysisFailureCode.orUnexpected(
                request.failureCode()
        );
        String errorMessage = WorldSettingNameNormalizer.displayName(
                request.errorMessage()
        );
        if (isBlank(errorMessage)) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_INPUT_INVALID);
        }
        String sourceErrorCode = request.sourceErrorCode() == null
                ? null
                : request.sourceErrorCode().trim();
        if (sourceErrorCode != null && sourceErrorCode.isEmpty()) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_INPUT_INVALID);
        }
        if (request.sourceReasonCode() != null
                && (failureCode != AnalysisFailureCode.COMPARISON_VALIDATION_FAILED
                || !WorldSettingErrorCode.WORLD_SETTING_COMPARISON_TARGET_INVALID
                .getCode()
                .equals(sourceErrorCode))) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_INPUT_INVALID);
        }
        return new NormalizedComparisonFailure(
                failureCode,
                errorMessage,
                sourceErrorCode,
                request.sourceReasonCode()
        );
    }

    private int candidateRefIndex(String candidateRef) {
        if (candidateRef == null || !candidateRef.matches("C[1-9][0-9]*")) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_INPUT_INVALID);
        }
        return Integer.parseInt(candidateRef.substring(1));
    }

    private String completionHash(WorkerWorldSettingComparisonBatchCompleteRequest request) {
        try {
            byte[] bytes = worldSettingWorkerMapper.toJsonNode(request)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available.", exception);
        }
    }

    private Map<UUID, WorldSetting> validateBatchContext(
            WorldSettingComparisonBatch batch,
            List<WorldSettingCandidate> candidates,
            WorkerWorldSettingComparisonBatchCompleteRequest request
    ) {
        JsonNode snapshot = batch.getContextSnapshotJson();
        if (snapshot == null || !snapshot.isObject()) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.BATCH_CONTEXT_NOT_INITIALIZED
            );
        }
        Map<UUID, Long> expectedVersions = new LinkedHashMap<>();
        for (WorkerWorldSettingComparisonBatchCompleteRequest.ContextVersion contextVersion
                : request.contextVersions()) {
            if (expectedVersions.put(
                    contextVersion.worldSettingId(),
                    contextVersion.version()
            ) != null) {
                throw invalidComparisonTarget(
                        WorldSettingComparisonValidationReason.CONTEXT_VERSION_DUPLICATED
                );
            }
        }
        if (!expectedVersions.equals(snapshotTargetVersions(snapshot))) {
            throw new AppException(
                    WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_CONTEXT_STALE
            );
        }

        List<WorldSetting> targets = expectedVersions.isEmpty()
                ? List.of()
                : worldSettingRepository.findAllComparisonTargetsForUpdate(
                        batch.getWork().getId(),
                        batch.getCategory(),
                        expectedVersions.keySet()
                );
        if (targets.size() != expectedVersions.size()
                || targets.stream().anyMatch(target ->
                target.getVersion() != expectedVersions.get(target.getId()))) {
            throw new AppException(
                    WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_CONTEXT_STALE
            );
        }

        Map<String, UUID> expectedExactTargets = snapshotExactTargets(snapshot);
        if (expectedExactTargets.size() != candidates.size()) {
            throw new AppException(
                    WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_CONTEXT_STALE
            );
        }
        for (WorldSettingCandidate candidate : candidates) {
            UUID currentExactTargetId = findExactTarget(candidate)
                    .map(WorldSetting::getId)
                    .orElse(null);
            if (!expectedExactTargets.containsKey(candidate.getComparisonCandidateRef())
                    || !Objects.equals(
                    expectedExactTargets.get(candidate.getComparisonCandidateRef()),
                    currentExactTargetId
            )) {
                throw new AppException(
                        WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_CONTEXT_STALE
                );
            }
        }

        Map<UUID, WorldSetting> targetsById = new LinkedHashMap<>();
        targets.forEach(target -> targetsById.put(target.getId(), target));
        return targetsById;
    }

    private Map<UUID, Long> snapshotTargetVersions(JsonNode snapshot) {
        Map<UUID, Long> versions = new LinkedHashMap<>();
        JsonNode items = snapshot.get("targetVersions");
        if (items == null || !items.isArray()) {
            return versions;
        }
        for (JsonNode item : items) {
            UUID worldSettingId = UUID.fromString(item.path("worldSettingId").asText());
            versions.put(worldSettingId, item.path("version").asLong());
        }
        return versions;
    }

    private Map<String, UUID> snapshotExactTargets(JsonNode snapshot) {
        Map<String, UUID> exactTargets = new LinkedHashMap<>();
        JsonNode items = snapshot.get("exactTargets");
        if (items == null || !items.isArray()) {
            return exactTargets;
        }
        for (JsonNode item : items) {
            JsonNode worldSettingId = item.get("worldSettingId");
            exactTargets.put(
                    item.path("candidateRef").asText(),
                    worldSettingId == null || worldSettingId.isNull()
                            ? null
                            : UUID.fromString(worldSettingId.asText())
            );
        }
        return exactTargets;
    }

    private Map<WorkerWorldSettingComparisonBatchCompleteRequest.Decision,
            List<WorldSettingCandidate>> validateBatchSourceCoverage(
            List<WorldSettingCandidate> candidates,
            List<WorkerWorldSettingComparisonBatchCompleteRequest.Decision> decisions
    ) {
        Map<String, WorldSettingCandidate> candidatesByRef = new LinkedHashMap<>();
        candidates.forEach(candidate -> candidatesByRef.put(
                candidate.getComparisonCandidateRef(),
                candidate
        ));
        Set<String> decisionRefs = new HashSet<>();
        Set<String> seenCandidateRefs = new HashSet<>();
        Map<WorkerWorldSettingComparisonBatchCompleteRequest.Decision,
                List<WorldSettingCandidate>> result = new LinkedHashMap<>();
        for (WorkerWorldSettingComparisonBatchCompleteRequest.Decision decision : decisions) {
            if (!decisionRefs.add(decision.decisionRef())) {
                throw invalidComparisonTarget(
                        WorldSettingComparisonValidationReason.BATCH_DECISION_REF_DUPLICATED
                );
            }
            List<WorldSettingCandidate> sources = new ArrayList<>();
            for (String candidateRef : decision.sourceCandidateRefs()) {
                if (!seenCandidateRefs.add(candidateRef)) {
                    throw invalidComparisonTarget(
                            WorldSettingComparisonValidationReason.BATCH_SOURCE_REF_DUPLICATED
                    );
                }
                WorldSettingCandidate candidate = candidatesByRef.get(candidateRef);
                if (candidate == null) {
                    throw invalidComparisonTarget(
                            WorldSettingComparisonValidationReason.BATCH_SOURCE_REF_UNKNOWN
                    );
                }
                sources.add(candidate);
            }
            sources.sort(Comparator.comparingInt(candidate -> candidateRefIndex(
                    candidate.getComparisonCandidateRef()
            )));
            result.put(decision, sources);
        }
        if (seenCandidateRefs.size() != candidatesByRef.size()) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.BATCH_SOURCE_COVERAGE_INVALID
            );
        }
        return result;
    }

    private void validateBatchDecision(
            WorldSettingComparisonBatch batch,
            List<WorldSettingCandidate> sources,
            WorldSetting target,
            WorkerWorldSettingComparisonBatchCompleteRequest.Decision request
    ) {
        WorldSettingSuggestedOperation operation = request.suggestedOperation();
        boolean batchLimitReview = operation == WorldSettingSuggestedOperation.REVIEW_REQUIRED
                && request.comparisonReviewReason()
                == WorldSettingComparisonReviewReason.BATCH_LIMIT_EXCEEDED;
        if (sources.isEmpty()
                || sources.stream().anyMatch(candidate ->
                candidate.getCategory() != batch.getCategory()
                        || !sameName(candidate.getScopeName(), batch.getRawScopeName()))) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.BATCH_SOURCE_SCOPE_INVALID
            );
        }
        if (sources.size() > 1
                && request.consolidationStatus() == WorldSettingConsolidationStatus.SINGLE) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.BATCH_CONSOLIDATION_STATUS_INVALID
            );
        }
        Set<UUID> resolvedTargetIds = new HashSet<>(
                worldSettingWorkerMapper.toUuidList(
                        batch.getResolvedTargetWorldSettingIds()
                )
        );
        if (target != null && !resolvedTargetIds.contains(target.getId())) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.BATCH_CANONICAL_SUBJECT_INVALID
            );
        }
        boolean canonicalNameMatches = target == null
                ? sameName(batch.getCanonicalSubjectName(), request.canonicalSubjectName())
                : sameName(target.getSubjectName(), request.canonicalSubjectName());
        if (!canonicalNameMatches) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.BATCH_CANONICAL_SUBJECT_INVALID
            );
        }
        if (batch.getSubjectResolutionType() == WorldSettingSubjectResolutionType.NEW
                && target != null) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.BATCH_CANONICAL_SUBJECT_INVALID
            );
        }
        if (batch.getSubjectResolutionType() == WorldSettingSubjectResolutionType.EXISTING
                && (target == null || resolvedTargetIds.size() != 1)) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.BATCH_CANONICAL_SUBJECT_INVALID
            );
        }

        Set<UUID> exactTargetIds = new HashSet<>();
        for (WorldSettingCandidate source : sources) {
            findExactTarget(source).map(WorldSetting::getId).ifPresent(exactTargetIds::add);
        }
        if (!batchLimitReview && (exactTargetIds.size() > 1
                || (exactTargetIds.size() == 1
                && (target == null || !exactTargetIds.contains(target.getId()))))) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.BATCH_CANONICAL_SUBJECT_INVALID
            );
        }

        List<String> rootPropertyNamesToMove = rootPropertyNamesToMove(request);
        if (operation != WorldSettingSuggestedOperation.ADD
                && !rootPropertyNamesToMove.isEmpty()) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.ROOT_PROPERTY_MOVE_NOT_ALLOWED
            );
        }
        if (operation == WorldSettingSuggestedOperation.REVIEW_REQUIRED) {
            if (batchLimitReview) {
                validateBatchLimitReview(
                        sources,
                        target,
                        resolvedTargetIds,
                        request
                );
            } else {
                validateBatchScopeReview(sources, target, request);
            }
            return;
        }
        if (request.comparisonReviewReason() != null) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.REVIEW_REASON_FORBIDDEN
            );
        }
        if (operation == WorldSettingSuggestedOperation.UPDATE
                || operation == WorldSettingSuggestedOperation.MERGE) {
            if (target == null || isBlank(request.matchedPropertyName())) {
                throw invalidComparisonTarget(
                        WorldSettingComparisonValidationReason.MATCHED_TARGET_REQUIRED
                );
            }
            WorldSetting.StoredPropertyPath storedPath = target.getStoredPropertyPath(
                    request.matchedScopeName(),
                    request.matchedPropertyName()
            );
            if (storedPath == null
                    || !sameName(storedPath.scopeName(), request.proposedScopeName())
                    || !sameName(storedPath.settingName(), request.proposedSettingName())
                    || sources.stream().anyMatch(source ->
                    !sameName(source.getScopeName(), storedPath.scopeName()))) {
                throw invalidComparisonTarget(
                        WorldSettingComparisonValidationReason.PROPOSED_PATH_MISMATCH
                );
            }
            return;
        }
        if (operation == WorldSettingSuggestedOperation.ADD) {
            if (!isBlank(request.matchedPropertyName())
                    || !isBlank(request.matchedScopeName())) {
                throw invalidComparisonTarget(
                        WorldSettingComparisonValidationReason.ADD_MATCHED_PATH_FORBIDDEN
                );
            }
            if (!isBlank(request.proposedScopeName())
                    && sameName(request.proposedScopeName(), request.proposedSettingName())) {
                throw invalidComparisonTarget(
                        WorldSettingComparisonValidationReason.SCOPE_SETTING_NAME_DUPLICATED
                );
            }
            validateRootPropertyMoves(target, request, rootPropertyNamesToMove);
            if (target != null && (target.hasProperty(
                    request.proposedScopeName(),
                    request.proposedSettingName()
            ) || target.hasPathConflict(
                    request.proposedScopeName(),
                    request.proposedSettingName()
            ))) {
                throw invalidComparisonTarget(
                        WorldSettingComparisonValidationReason.PROPOSED_PATH_CONFLICT
                );
            }
            return;
        }
        if (operation == WorldSettingSuggestedOperation.EXCLUDE
                && !isBlank(request.matchedPropertyName())) {
            WorldSetting.StoredPropertyPath storedPath = target == null
                    ? null
                    : target.getStoredPropertyPath(
                            request.matchedScopeName(),
                            request.matchedPropertyName()
                    );
            if (storedPath == null || sources.stream().anyMatch(source ->
                    !sameName(source.getScopeName(), storedPath.scopeName()))) {
                throw invalidComparisonTarget(
                        WorldSettingComparisonValidationReason.EXCLUDE_MATCHED_PATH_INVALID
                );
            }
        } else if (operation == WorldSettingSuggestedOperation.EXCLUDE
                && !isBlank(request.matchedScopeName())) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.EXCLUDE_MATCHED_SCOPE_WITHOUT_PROPERTY
            );
        }
    }

    private void validateBatchLimitReview(
            List<WorldSettingCandidate> sources,
            WorldSetting target,
            Set<UUID> resolvedTargetIds,
            WorkerWorldSettingComparisonBatchCompleteRequest.Decision request
    ) {
        if (sources.size() != 1
                || !isBlank(request.matchedScopeName())
                || !isBlank(request.matchedPropertyName())
                || !rootPropertyNamesToMove(request).isEmpty()) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.BATCH_CONSOLIDATION_STATUS_INVALID
            );
        }
        boolean hasSingleResolvedTarget = resolvedTargetIds.size() == 1;
        if ((hasSingleResolvedTarget && (target == null
                || !resolvedTargetIds.contains(target.getId())))
                || (!hasSingleResolvedTarget && target != null)) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.BATCH_CANONICAL_SUBJECT_INVALID
            );
        }

        WorldSettingCandidate source = sources.getFirst();
        long sourceValueCount = java.util.Arrays.stream(
                        source.getExtractedValue().split("\\R", -1)
                )
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .count();
        WorldSettingConsolidationStatus expectedStatus = sourceValueCount > 1
                ? WorldSettingConsolidationStatus.CONFLICT
                : WorldSettingConsolidationStatus.SINGLE;
        if (request.consolidationStatus() != expectedStatus
                || !Objects.equals(request.proposedScopeName(), source.getScopeName())
                || !Objects.equals(request.proposedSettingName(), source.getSettingName())
                || !Objects.equals(request.proposedValue(), source.getExtractedValue())) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.BATCH_CONSOLIDATION_STATUS_INVALID
            );
        }
    }

    private void validateRootPropertyMoves(
            WorldSetting target,
            WorkerWorldSettingComparisonBatchCompleteRequest.Decision request,
            List<String> rootPropertyNames
    ) {
        if (rootPropertyNames.isEmpty()) {
            return;
        }
        if (target == null || isBlank(request.proposedScopeName())) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.ROOT_PROPERTY_MOVE_NOT_ALLOWED
            );
        }
        Set<String> normalizedNames = new HashSet<>();
        for (String requestedName : rootPropertyNames) {
            String normalizedName = WorldSettingNameNormalizer.duplicateKey(requestedName);
            if (!normalizedNames.add(normalizedName)) {
                throw invalidComparisonTarget(
                        WorldSettingComparisonValidationReason.ROOT_PROPERTY_MOVE_DUPLICATED
                );
            }
            WorldSetting.StoredPropertyPath storedPath = target.getStoredPropertyPath(
                    null,
                    requestedName
            );
            if (storedPath == null
                    || sameName(request.proposedScopeName(), storedPath.settingName())
                    || sameName(request.proposedSettingName(), storedPath.settingName())) {
                throw invalidComparisonTarget(
                        WorldSettingComparisonValidationReason.ROOT_PROPERTY_MOVE_INVALID
                );
            }
            if (target.hasProperty(request.proposedScopeName(), storedPath.settingName())
                    || target.hasPathConflict(
                    request.proposedScopeName(),
                    storedPath.settingName()
            )) {
                throw invalidComparisonTarget(
                        WorldSettingComparisonValidationReason.PROPOSED_PATH_CONFLICT
                );
            }
        }
    }

    private List<ExistingRootPropertyMoveSnapshot> rootPropertyMoveSnapshots(
            WorldSetting target,
            WorkerWorldSettingComparisonBatchCompleteRequest.Decision request
    ) {
        if (target == null) {
            return List.of();
        }
        return rootPropertyNamesToMove(request).stream()
                .map(requestedName -> target.getStoredPropertyPath(null, requestedName))
                .map(storedPath -> new ExistingRootPropertyMoveSnapshot(
                        storedPath.settingName(),
                        target.getPropertyValue(null, storedPath.settingName())
                ))
                .toList();
    }

    private List<String> rootPropertyNamesToMove(
            WorkerWorldSettingComparisonBatchCompleteRequest.Decision request
    ) {
        return request.existingRootPropertyNamesToMove() == null
                ? List.of()
                : request.existingRootPropertyNamesToMove();
    }

    private void validateSyntheticScopeChildCount(
            WorldSettingComparisonBatch batch,
            List<WorkerWorldSettingComparisonBatchCompleteRequest.Decision> decisions,
            Map<UUID, WorldSetting> contextTargets
    ) {
        Map<String, Set<String>> childrenByTargetScope = new LinkedHashMap<>();
        Set<String> syntheticTargetScopes = new HashSet<>();
        for (WorkerWorldSettingComparisonBatchCompleteRequest.Decision decision : decisions) {
            if (decision.suggestedOperation() != WorldSettingSuggestedOperation.ADD
                    || isBlank(decision.proposedScopeName())) {
                continue;
            }
            WorldSetting target = decision.targetWorldSettingId() == null
                    ? null
                    : contextTargets.get(decision.targetWorldSettingId());
            String targetKey = target == null
                    ? "NEW|" + WorldSettingNameNormalizer.duplicateKey(
                            decision.canonicalSubjectName()
                    )
                    : "EXISTING|" + target.getId();
            String targetScopeKey = targetKey
                    + "|"
                    + WorldSettingNameNormalizer.duplicateKey(decision.proposedScopeName());
            Set<String> children = childrenByTargetScope.computeIfAbsent(
                    targetScopeKey,
                    ignored -> existingScopedSettingNames(target, decision.proposedScopeName())
            );
            children.add(WorldSettingNameNormalizer.duplicateKey(
                    decision.proposedSettingName()
            ));
            rootPropertyNamesToMove(decision).stream()
                    .map(WorldSettingNameNormalizer::duplicateKey)
                    .forEach(children::add);
            if (!sameName(batch.getRawScopeName(), decision.proposedScopeName())) {
                syntheticTargetScopes.add(targetScopeKey);
            }
        }
        if (syntheticTargetScopes.stream()
                .anyMatch(targetScope -> childrenByTargetScope.get(targetScope).size() < 2)) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.SYNTHETIC_SCOPE_SINGLETON
            );
        }
    }

    private void validateBatchDecisionInteractions(
            List<WorkerWorldSettingComparisonBatchCompleteRequest.Decision> decisions,
            Set<String> requestedRootMoveKeys
    ) {
        Set<String> occupiedFinalPaths = new HashSet<>();
        Map<String, Boolean> scopedTopLevelKinds = new HashMap<>();
        for (WorkerWorldSettingComparisonBatchCompleteRequest.Decision decision : decisions) {
            WorldSettingSuggestedOperation operation = decision.suggestedOperation();
            String targetKey = batchDecisionTargetKey(decision);
            if (operation == WorldSettingSuggestedOperation.ADD
                    || operation == WorldSettingSuggestedOperation.UPDATE
                    || operation == WorldSettingSuggestedOperation.MERGE) {
                boolean scoped = !isBlank(decision.proposedScopeName());
                registerBatchTopLevelKind(
                        scopedTopLevelKinds,
                        targetKey,
                        scoped ? decision.proposedScopeName() : decision.proposedSettingName(),
                        scoped
                );
                String proposedPath = targetKey
                        + "|"
                        + Objects.toString(
                                WorldSettingNameNormalizer.duplicateKey(
                                        decision.proposedScopeName()
                                ),
                                "<root>"
                        )
                        + "|"
                        + WorldSettingNameNormalizer.duplicateKey(
                                decision.proposedSettingName()
                        );
                if (!occupiedFinalPaths.add(proposedPath)) {
                    throw invalidComparisonTarget(
                            WorldSettingComparisonValidationReason.BATCH_PROPOSED_PATH_DUPLICATED
                    );
                }
            }
            for (String rootPropertyName : rootPropertyNamesToMove(decision)) {
                registerBatchTopLevelKind(
                        scopedTopLevelKinds,
                        targetKey,
                        decision.proposedScopeName(),
                        true
                );
                String moveDestinationPath = targetKey
                        + "|"
                        + WorldSettingNameNormalizer.duplicateKey(
                                decision.proposedScopeName()
                        )
                        + "|"
                        + WorldSettingNameNormalizer.duplicateKey(rootPropertyName);
                if (!occupiedFinalPaths.add(moveDestinationPath)) {
                    throw invalidComparisonTarget(
                            WorldSettingComparisonValidationReason.BATCH_PROPOSED_PATH_DUPLICATED
                    );
                }
            }
            if ((operation == WorldSettingSuggestedOperation.UPDATE
                    || operation == WorldSettingSuggestedOperation.MERGE)
                    && decision.targetWorldSettingId() != null
                    && isBlank(decision.matchedScopeName())
                    && !isBlank(decision.matchedPropertyName())) {
                String matchedRootKey = decision.targetWorldSettingId()
                        + "|"
                        + WorldSettingNameNormalizer.duplicateKey(
                                decision.matchedPropertyName()
                        );
                if (requestedRootMoveKeys.contains(matchedRootKey)) {
                    throw invalidComparisonTarget(
                            WorldSettingComparisonValidationReason.ROOT_PROPERTY_MOVE_CONFLICT
                    );
                }
            }
        }
    }

    private void registerBatchTopLevelKind(
            Map<String, Boolean> scopedTopLevelKinds,
            String targetKey,
            String topLevelName,
            boolean scoped
    ) {
        String topLevelKey = targetKey
                + "|"
                + WorldSettingNameNormalizer.duplicateKey(topLevelName);
        Boolean previousKind = scopedTopLevelKinds.putIfAbsent(topLevelKey, scoped);
        if (previousKind != null && previousKind != scoped) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.PROPOSED_PATH_CONFLICT
            );
        }
    }

    private String batchDecisionTargetKey(
            WorkerWorldSettingComparisonBatchCompleteRequest.Decision decision
    ) {
        return decision.targetWorldSettingId() == null
                ? "NEW|" + WorldSettingNameNormalizer.duplicateKey(
                        decision.canonicalSubjectName()
                )
                : "EXISTING|" + decision.targetWorldSettingId();
    }

    private Set<String> existingScopedSettingNames(
            WorldSetting target,
            String scopeName
    ) {
        if (target == null) {
            return new HashSet<>();
        }
        return target.getProperties().stream()
                .filter(property -> sameName(property.scopeName(), scopeName))
                .map(property -> WorldSettingNameNormalizer.duplicateKey(
                        property.settingName()
                ))
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
    }

    private void validateBatchScopeReview(
            List<WorldSettingCandidate> sources,
            WorldSetting target,
            WorkerWorldSettingComparisonBatchCompleteRequest.Decision request
    ) {
        if (request.comparisonReviewReason()
                != WorldSettingComparisonReviewReason.SCOPE_UNRESOLVED) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.SCOPE_REVIEW_REASON_INVALID
            );
        }
        if (target == null) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.SCOPE_REVIEW_TARGET_REQUIRED
            );
        }
        if (sources.stream().anyMatch(source -> source.getScopeName() != null)) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.SCOPE_REVIEW_CANDIDATE_ALREADY_SCOPED
            );
        }
        if (isBlank(request.matchedScopeName()) || isBlank(request.matchedPropertyName())) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.SCOPE_REVIEW_MATCHED_PATH_REQUIRED
            );
        }
        WorldSetting.StoredPropertyPath storedPath = target.getStoredPropertyPath(
                request.matchedScopeName(),
                request.matchedPropertyName()
        );
        if (storedPath == null
                || storedPath.scopeName() == null
                || sources.stream().anyMatch(source ->
                !sameName(source.getSettingName(), storedPath.settingName()))
                || !isBlank(request.proposedScopeName())
                || !sources.stream().allMatch(source ->
                sameName(source.getSettingName(), request.proposedSettingName()))) {
            throw invalidComparisonTarget(
                    WorldSettingComparisonValidationReason.SCOPE_REVIEW_MATCHED_PATH_INVALID
            );
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
        WorldSettingCandidate candidate = getOwnedCandidateForUpdate(analysisJob, candidateId);
        if (candidate.getComparisonStatus() != WorldSettingComparisonStatus.PROCESSING) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
        }
        return candidate;
    }

    private WorldSettingCandidate getOwnedCandidateForUpdate(
            AnalysisJob analysisJob,
            UUID candidateId
    ) {
        WorldSettingCandidate candidate = worldSettingCandidateRepository
                .findByIdAndWorkIdForUpdate(candidateId, analysisJob.getWork().getId())
                .orElseThrow(() -> new AppException(
                        WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_NOT_FOUND
                ));
        validateCandidateOwnership(analysisJob, candidate);
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

    private AppException invalidComparisonTarget(
            WorldSettingComparisonValidationReason reason
    ) {
        return new AppException(
                WorldSettingErrorCode.WORLD_SETTING_COMPARISON_TARGET_INVALID,
                Map.of("reasonCode", reason.name())
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean sameName(String left, String right) {
        return Objects.equals(
                WorldSettingNameNormalizer.duplicateKey(left),
                WorldSettingNameNormalizer.duplicateKey(right)
        );
    }

    private record SubjectResolution(
            WorldSettingSubjectResolutionType type,
            String canonicalKey,
            String canonicalName,
            List<UUID> targetIds
    ) {
    }

    private record NormalizedComparisonFailure(
            AnalysisFailureCode failureCode,
            String errorMessage,
            String sourceErrorCode,
            WorldSettingComparisonValidationReason sourceReasonCode
    ) {
    }
}
