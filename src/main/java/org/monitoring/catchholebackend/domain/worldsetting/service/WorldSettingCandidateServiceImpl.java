package org.monitoring.catchholebackend.domain.worldsetting.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobEpisodeRange;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateGroupConfirmRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateGroupDismissRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateConfirmRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateDecisionUpdateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateDecisionUpdateItem;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateDismissRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateGroupActionResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateGroupResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateListResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateDecisionUpdateResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingTokenInterruptedResumeResponse;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecision;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecision.ExistingRootPropertyMoveSnapshot;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecisionSource;
import org.monitoring.catchholebackend.domain.worldsetting.exception.WorldSettingErrorCode;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingMapper;
import org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingNameNormalizer;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateBatchCounts;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingComparisonDecisionSourceRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingConsolidationStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingRecomparisonReason;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingRecomparisonScope;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorldSettingCandidateServiceImpl implements WorldSettingCandidateService {

    private final WorkRepository workRepository;
    private final UploadBatchRepository uploadBatchRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final WorldSettingRepository worldSettingRepository;
    private final WorldSettingCandidateRepository worldSettingCandidateRepository;
    private final WorldSettingComparisonDecisionSourceRepository comparisonDecisionSourceRepository;
    private final WorldSettingMapper worldSettingMapper;
    private final AiTokenService aiTokenService;

    @Override
    public WorldSettingCandidateListResponse getCandidates(
            Long memberId,
            UUID workId,
            UUID batchId,
            WorldSettingReviewStatus reviewStatus,
            WorldSettingCategory category,
            WorldSettingSuggestedOperation operation,
            int page,
            int size
    ) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        validateBatch(work, batchId);
        List<WorldSettingCandidate> candidates = worldSettingCandidateRepository.findReviewList(
                work.getId(),
                batchId,
                reviewStatus,
                category,
                operation,
                operation == null || operation == WorldSettingSuggestedOperation.REVIEW_REQUIRED
                        ? null
                        : WorldSettingOperation.valueOf(operation.name())
        );
        WorldSettingCandidateBatchCounts counts = worldSettingCandidateRepository.countReviewSummary(
                work.getId(),
                batchId,
                WorldSettingReviewStatus.PENDING_REVIEW,
                WorldSettingComparisonStatus.PENDING,
                WorldSettingComparisonStatus.PROCESSING,
                WorldSettingComparisonStatus.FAILED,
                AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED,
                WorldSettingComparisonStatus.RECOMPARISON_REQUIRED,
                WorldSettingComparisonStatus.COMPLETED,
                WorldSettingConsolidationStatus.CONFLICT
        );
        AnalysisJobEpisodeRange episodeRange =
                analysisJobRepository.findEpisodeRangeByWorkIdAndBatchId(work.getId(), batchId);
        long activeComparisonJobCount = analysisJobRepository.countActiveComparisonsByBatchIds(
                        work.getId(),
                        List.of(batchId),
                        AnalysisJobType.WORLD_SETTING_COMPARISON,
                        List.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.RUNNING)
                )
                .stream()
                .mapToLong(countsByBatch -> countsByBatch.getActiveComparisonCount())
                .findFirst()
                .orElse(0L);
        Map<String, List<WorldSettingCandidate>> candidatesByGroup = new LinkedHashMap<>();
        for (WorldSettingCandidate candidate : candidates) {
            candidatesByGroup.computeIfAbsent(groupKey(candidate), ignored -> new ArrayList<>())
                    .add(candidate);
        }
        List<WorldSettingCandidateGroupResponse> allGroups = candidatesByGroup.entrySet().stream()
                .map(entry -> worldSettingMapper.toCandidateGroupResponse(entry.getKey(), entry.getValue()))
                .toList();
        int fromIndex = (int) Math.min((long) page * size, allGroups.size());
        int toIndex = Math.min(fromIndex + size, allGroups.size());
        int totalPages = allGroups.isEmpty() ? 0 : (allGroups.size() + size - 1) / size;
        PageResponse<WorldSettingCandidateGroupResponse> groupPage = new PageResponse<>(
                allGroups.subList(fromIndex, toIndex),
                page,
                size,
                allGroups.size(),
                totalPages,
                page + 1 < totalPages
        );
        return new WorldSettingCandidateListResponse(
                batchId,
                episodeRange.getEpisodeStartNo(),
                episodeRange.getEpisodeEndNo(),
                episodeRange.getEpisodeCount(),
                counts.getTotalCandidateCount(),
                counts.getReviewedCandidateCount(),
                counts.getPendingCandidateCount(),
                counts.getPendingComparisonCount(),
                counts.getProcessingComparisonCount(),
                activeComparisonJobCount,
                counts.getFailedComparisonCount(),
                counts.getTokenInterruptedComparisonCount(),
                counts.getTokenInterruptedComparisonCount() > 0,
                counts.getRecomparisonRequiredCount(),
                counts.getConflictCandidateCount(),
                groupPage
        );
    }

    @Override
    public WorldSettingCandidateResponse getCandidate(
            Long memberId,
            UUID workId,
            UUID batchId,
            UUID candidateId
    ) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        validateBatch(work, batchId);
        WorldSettingCandidate candidate = worldSettingCandidateRepository
                .findByIdAndWorkIdAndAnalysisJobBatchId(candidateId, work.getId(), batchId)
                .orElseThrow(() -> new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_NOT_FOUND));
        return worldSettingMapper.toCandidateResponse(candidate);
    }

    @Override
    @Transactional
    public WorldSettingCandidateDecisionUpdateResponse updateCandidateDecisions(
            Long memberId,
            UUID workId,
            WorldSettingCandidateDecisionUpdateRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        validateBatch(work, request.batchId());
        Set<UUID> candidateIds = request.candidates().stream()
                .map(WorldSettingCandidateDecisionUpdateItem::candidateId)
                .collect(java.util.stream.Collectors.toSet());
        if (candidateIds.size() != request.candidates().size()) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_SELECTION_INVALID);
        }
        List<WorldSettingCandidate> candidates = worldSettingCandidateRepository.findAllByIdsAndBatchForUpdate(
                work.getId(),
                request.batchId(),
                candidateIds
        );
        if (candidates.size() != candidateIds.size()) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_SELECTION_INVALID);
        }
        validateSameCandidateGroup(candidates);
        Map<UUID, WorldSettingCandidateDecisionUpdateItem> decisionsById = request.candidates().stream()
                .collect(java.util.stream.Collectors.toMap(
                        WorldSettingCandidateDecisionUpdateItem::candidateId,
                        decision -> decision
                ));
        String updatedGroupKey = null;
        for (WorldSettingCandidate candidate : candidates) {
            WorldSettingCandidateDecisionUpdateItem decision = decisionsById.get(candidate.getId());
            String decisionGroupKey = groupKey(decision.category(), decision.subjectName());
            if (updatedGroupKey != null && !updatedGroupKey.equals(decisionGroupKey)) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_SELECTION_INVALID);
            }
            updatedGroupKey = decisionGroupKey;
            if (candidate.getComparisonDecision() != null
                    && !candidate.getComparisonDecision()
                            .getExistingRootPropertyMoveSnapshots()
                            .isEmpty()
                    && isAuthorEditedDecision(
                            candidate,
                            decision.operation(),
                            decision.category(),
                            decision.subjectName(),
                            decision.scopeName(),
                            decision.settingName(),
                            decision.value()
                    )) {
                candidate.getComparisonDecision().disableRootPropertyMoves();
            }
            candidate.updateDecisionDraft(
                    decision.operation(),
                    decision.category(),
                    decision.subjectName(),
                    decision.scopeName(),
                    decision.settingName(),
                    decision.value(),
                    decision.reviewNote()
            );
        }
        worldSettingCandidateRepository.flush();
        return new WorldSettingCandidateDecisionUpdateResponse(
                updatedGroupKey,
                candidates.stream().map(worldSettingMapper::toCandidateResponse).toList()
        );
    }

    @Override
    @Transactional
    public WorldSettingCandidateResponse retryComparison(Long memberId, UUID workId, UUID candidateId) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        WorldSettingCandidate candidate = getCandidateForUpdate(candidateId, work.getId());
        candidate.requestRecomparison();
        enqueueRecomparisonJobIfAbsent(memberId, candidate);
        worldSettingCandidateRepository.flush();
        return worldSettingMapper.toCandidateResponse(candidate);
    }

    @Override
    @Transactional
    public WorldSettingTokenInterruptedResumeResponse resumeTokenInterruptedComparisons(
            Long memberId,
            UUID workId,
            UUID batchId
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        validateBatch(work, batchId);
        List<WorldSettingCandidate> interruptedCandidates = worldSettingCandidateRepository
                .findTokenInterruptedByBatchForUpdate(
                        work.getId(),
                        batchId,
                        WorldSettingReviewStatus.PENDING_REVIEW,
                        WorldSettingComparisonStatus.FAILED,
                        AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED
                );
        long resumedCandidateCount = 0;
        if (!interruptedCandidates.isEmpty()) {
            aiTokenService.ensureComparisonCanStart(memberId);
            for (WorldSettingCandidate candidate : interruptedCandidates) {
                boolean activeJobExists = analysisJobRepository
                        .findFirstByWorldSettingCandidateIdAndStatusInOrderByCreatedAtDesc(
                                candidate.getId(),
                                List.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.RUNNING)
                        )
                        .isPresent();
                if (activeJobExists) {
                    continue;
                }
                candidate.resumeTokenInterruptedComparison();
                analysisJobRepository.save(AnalysisJob.createWorldSettingComparison(candidate));
                resumedCandidateCount++;
            }
            worldSettingCandidateRepository.flush();
        }

        WorldSettingCandidateBatchCounts counts = worldSettingCandidateRepository.countReviewSummary(
                work.getId(),
                batchId,
                WorldSettingReviewStatus.PENDING_REVIEW,
                WorldSettingComparisonStatus.PENDING,
                WorldSettingComparisonStatus.PROCESSING,
                WorldSettingComparisonStatus.FAILED,
                AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED,
                WorldSettingComparisonStatus.RECOMPARISON_REQUIRED,
                WorldSettingComparisonStatus.COMPLETED,
                WorldSettingConsolidationStatus.CONFLICT
        );
        return new WorldSettingTokenInterruptedResumeResponse(
                batchId,
                resumedCandidateCount,
                counts.getPendingComparisonCount() + counts.getProcessingComparisonCount(),
                counts.getTokenInterruptedComparisonCount()
        );
    }

    @Override
    @Transactional
    public WorldSettingCandidateConfirmResult confirmCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId,
            WorldSettingCandidateConfirmRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        WorldSettingCandidate candidate = getCandidateForUpdate(candidateId, work.getId());
        requireSingletonComparisonDecision(candidate);

        if (candidate.getReviewStatus() == WorldSettingReviewStatus.CONFIRMED) {
            candidate.confirm(
                    request.operation(),
                    request.category(),
                    request.subjectName(),
                    request.scopeName(),
                    request.settingName(),
                    request.value(),
                    request.reviewNote(),
                    work.getMember(),
                    candidate.getTargetWorldSetting()
            );
            return WorldSettingCandidateConfirmResult.confirmed(
                    worldSettingMapper.toCandidateResponse(candidate)
            );
        }
        if (candidate.getReviewStatus() == WorldSettingReviewStatus.DISMISSED) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT);
        }

        validateComparisonReadyAndOperation(candidate, request);
        if (isAuthorEditedDecision(candidate, request)) {
            WorldSetting currentTarget = worldSettingRepository.findByIdentityForUpdate(
                    work.getId(),
                    request.category(),
                    WorldSettingNameNormalizer.duplicateKey(request.subjectName())
            ).orElse(null);
            validateAuthorDecisionCompatible(
                    currentTarget,
                    request.operation(),
                    request.scopeName(),
                    request.settingName()
            );
            WorldSetting appliedTarget;
            if (currentTarget == null) {
                appliedTarget = worldSettingRepository.saveAndFlush(worldSettingMapper.toEntity(work, request));
            } else {
                currentTarget.applyProperty(request.scopeName(), request.settingName(), request.value());
                worldSettingRepository.flush();
                appliedTarget = currentTarget;
            }
            candidate.confirm(
                    request.operation(),
                    request.category(),
                    request.subjectName(),
                    request.scopeName(),
                    request.settingName(),
                    request.value(),
                    request.reviewNote(),
                    work.getMember(),
                    appliedTarget
            );
            worldSettingCandidateRepository.flush();
            return WorldSettingCandidateConfirmResult.confirmed(
                    worldSettingMapper.toCandidateResponse(candidate)
            );
        }
        WorldSetting comparedTarget = candidate.getTargetWorldSetting();
        WorldSetting currentTarget;
        if (comparedTarget == null) {
            if (!matchesComparedIdentity(candidate, null, request)) {
                return markRecomparisonRequired(candidate);
            }
            String normalizedSubjectName = WorldSettingNameNormalizer.duplicateKey(request.subjectName());
            currentTarget = worldSettingRepository.findByIdentityForUpdate(
                    work.getId(),
                    request.category(),
                    normalizedSubjectName
            ).orElse(null);
            if (currentTarget != null) {
                return markRecomparisonRequired(candidate);
            }
        } else {
            currentTarget = worldSettingRepository.findByIdAndWorkIdForUpdate(
                    comparedTarget.getId(),
                    work.getId()
            ).orElse(null);
            if (currentTarget == null || !matchesComparedIdentity(candidate, currentTarget, request)) {
                return markRecomparisonRequired(candidate);
            }
        }

        WorldSetting appliedTarget;
        if (currentTarget == null) {
            if (request.operation() != WorldSettingOperation.ADD) {
                return markRecomparisonRequired(candidate);
            }
            appliedTarget = worldSettingRepository.saveAndFlush(
                    worldSettingMapper.toEntity(work, request)
            );
        } else {
            if (!isCurrentPropertyCompatible(candidate, currentTarget, request)) {
                return markRecomparisonRequired(candidate);
            }
            currentTarget.applyProperty(request.scopeName(), request.settingName(), request.value());
            worldSettingRepository.flush();
            appliedTarget = currentTarget;
        }

        candidate.confirm(
                request.operation(),
                request.category(),
                request.subjectName(),
                request.scopeName(),
                request.settingName(),
                request.value(),
                request.reviewNote(),
                work.getMember(),
                appliedTarget
        );
        worldSettingCandidateRepository.flush();
        return WorldSettingCandidateConfirmResult.confirmed(
                worldSettingMapper.toCandidateResponse(candidate)
        );
    }

    @Override
    @Transactional
    public WorldSettingCandidateResponse dismissCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId,
            WorldSettingCandidateDismissRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        WorldSettingCandidate candidate = getCandidateForUpdate(candidateId, work.getId());
        requireSingletonComparisonDecision(candidate);
        candidate.dismiss(request.reviewNote(), work.getMember());
        worldSettingCandidateRepository.flush();
        return worldSettingMapper.toCandidateResponse(candidate);
    }

    @Override
    @Transactional
    public WorldSettingCandidateGroupConfirmResult confirmCandidateGroup(
            Long memberId,
            UUID workId,
            WorldSettingCandidateGroupConfirmRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        validateBatch(work, request.batchId());
        Map<UUID, WorldSettingCandidateGroupConfirmRequest.Decision> decisionsById =
                decisionsById(request.candidates());
        List<WorldSettingCandidate> candidates = worldSettingCandidateRepository
                .findAllByIdsAndBatchForUpdate(work.getId(), request.batchId(), decisionsById.keySet());
        validateRequestedCandidates(candidates, decisionsById.size());
        validateCompleteComparisonDecisionMembership(candidates, decisionsById.keySet());
        String selectedGroupKey = validateSameCandidateGroup(candidates);

        boolean allReviewed = candidates.stream()
                .noneMatch(candidate -> candidate.getReviewStatus() == WorldSettingReviewStatus.PENDING_REVIEW);
        if (allReviewed) {
            for (WorldSettingCandidate candidate : candidates) {
                WorldSettingCandidateGroupConfirmRequest.Decision decision = decisionsById.get(candidate.getId());
                if (decision.operation() == WorldSettingOperation.EXCLUDE) {
                    candidate.dismiss(decision.reviewNote(), work.getMember());
                } else {
                    candidate.confirm(
                            decision.operation(),
                            decision.category(),
                            decision.subjectName(),
                            decision.scopeName(),
                            decision.settingName(),
                            decision.value(),
                            decision.reviewNote(),
                            work.getMember(),
                            candidate.getTargetWorldSetting()
                    );
                }
            }
            WorldSetting appliedTarget = singleAppliedTarget(candidates.stream()
                    .map(WorldSettingCandidate::getTargetWorldSetting)
                    .filter(Objects::nonNull)
                    .toList());
            return WorldSettingCandidateGroupConfirmResult.confirmed(
                    worldSettingMapper.toCandidateGroupActionResponse(
                            selectedGroupKey,
                            candidates,
                            appliedTarget
                    )
            );
        }
        if (candidates.stream().anyMatch(candidate -> candidate.getReviewStatus()
                != WorldSettingReviewStatus.PENDING_REVIEW)) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT);
        }
        if (candidates.stream().anyMatch(candidate -> candidate.getComparisonStatus()
                != WorldSettingComparisonStatus.COMPLETED)) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_NOT_READY);
        }

        validateScopeReviewDecisionDrafts(candidates, decisionsById);
        validateResolvedConflicts(candidates, decisionsById);
        Set<UUID> rootMoveDecisionIds = rootMoveDecisionIdsToApply(
                candidates,
                decisionsById
        );
        validateDistinctSettingNames(candidates, decisionsById, rootMoveDecisionIds);
        List<WorldSettingCandidate> appliedCandidates = candidates.stream()
                .filter(candidate -> decisionsById.get(candidate.getId()).operation()
                        != WorldSettingOperation.EXCLUDE)
                .toList();
        if (appliedCandidates.isEmpty()) {
            candidates.forEach(candidate -> candidate.dismiss(
                    decisionsById.get(candidate.getId()).reviewNote(),
                    work.getMember()
            ));
            worldSettingCandidateRepository.flush();
            return WorldSettingCandidateGroupConfirmResult.confirmed(
                    worldSettingMapper.toCandidateGroupActionResponse(selectedGroupKey, candidates, null)
            );
        }

        Map<String, List<WorldSettingCandidate>> candidatesByFinalTarget = new TreeMap<>();
        for (WorldSettingCandidate candidate : appliedCandidates) {
            WorldSettingCandidateGroupConfirmRequest.Decision decision = decisionsById.get(candidate.getId());
            candidatesByFinalTarget.computeIfAbsent(
                    groupKey(decision.category(), decision.subjectName()),
                    ignored -> new ArrayList<>()
            ).add(candidate);
        }

        Map<String, WorldSetting> currentTargetsByKey = new LinkedHashMap<>();
        for (Map.Entry<String, List<WorldSettingCandidate>> entry : candidatesByFinalTarget.entrySet()) {
            List<WorldSettingCandidate> targetCandidates = entry.getValue();
            WorldSettingCandidateGroupConfirmRequest.Decision representativeDecision =
                    decisionsById.get(targetCandidates.getFirst().getId());
            WorldSetting currentTarget = worldSettingRepository.findByIdentityForUpdate(
                    work.getId(),
                    representativeDecision.category(),
                    WorldSettingNameNormalizer.duplicateKey(representativeDecision.subjectName())
            ).orElse(null);
            currentTargetsByKey.put(entry.getKey(), currentTarget);

            WorldSettingRecomparisonReason targetConflict = targetConflict(
                    targetCandidates,
                    decisionsById,
                    currentTarget
            );
            if (targetConflict != null) {
                return markGroupRecomparisonRequired(
                        work,
                        request.batchId(),
                        selectedGroupKey,
                        targetConflict
                );
            }

            Map<UUID, WorldSettingRecomparisonReason> conflicts = propertyConflicts(
                    targetCandidates,
                    decisionsById,
                    currentTarget,
                    rootMoveDecisionIds
            );
            if (!conflicts.isEmpty()) {
                WorldSettingRecomparisonReason reason = conflicts.values().iterator().next();
                for (WorldSettingCandidate candidate : targetCandidates) {
                    WorldSettingRecomparisonReason candidateReason = conflicts.get(candidate.getId());
                    if (candidateReason != null) {
                        candidate.markRecomparisonRequired(candidateReason.getMessage());
                    }
                }
                worldSettingCandidateRepository.flush();
                return WorldSettingCandidateGroupConfirmResult.recomparisonRequired(
                        WorldSettingRecomparisonScope.ROW,
                        reason,
                        List.copyOf(conflicts.keySet())
                );
            }
            validateAuthorEditedDecisions(targetCandidates, decisionsById, currentTarget);
        }

        Map<UUID, WorldSetting> appliedTargetsByCandidateId = new LinkedHashMap<>();
        for (Map.Entry<String, List<WorldSettingCandidate>> entry : candidatesByFinalTarget.entrySet()) {
            List<WorldSettingCandidate> targetCandidates = entry.getValue();
            WorldSettingCandidateGroupConfirmRequest.Decision representativeDecision =
                    decisionsById.get(targetCandidates.getFirst().getId());
            Map<String, WorldSetting.Property> propertiesByPath = new LinkedHashMap<>();
            Map<String, WorldSetting.RootPropertyMove> rootMovesByPath = new LinkedHashMap<>();
            for (WorldSettingCandidate candidate : targetCandidates) {
                WorldSettingCandidateGroupConfirmRequest.Decision decision = decisionsById.get(candidate.getId());
                propertiesByPath.putIfAbsent(
                        propertyPathKey(decision.scopeName(), decision.settingName()),
                        new WorldSetting.Property(
                        decision.scopeName(),
                        decision.settingName(),
                        decision.value()
                        )
                );
                if (shouldApplyRootPropertyMoves(candidate, rootMoveDecisionIds)) {
                    for (ExistingRootPropertyMoveSnapshot snapshot
                            : candidate.getComparisonDecision()
                                    .getExistingRootPropertyMoveSnapshots()) {
                        WorldSetting.RootPropertyMove move = new WorldSetting.RootPropertyMove(
                                snapshot.settingName(),
                                decision.scopeName()
                        );
                        rootMovesByPath.putIfAbsent(
                                propertyPathKey(move.scopeName(), move.settingName()),
                                move
                        );
                    }
                }
            }
            List<WorldSetting.Property> properties = List.copyOf(propertiesByPath.values());
            WorldSetting currentTarget = currentTargetsByKey.get(entry.getKey());
            WorldSetting appliedTarget;
            if (currentTarget == null) {
                appliedTarget = worldSettingRepository.saveAndFlush(WorldSetting.create(
                        work,
                        representativeDecision.category(),
                        representativeDecision.subjectName(),
                        properties
                ));
            } else {
                currentTarget.applyRootPropertyMovesAndProperties(
                        List.copyOf(rootMovesByPath.values()),
                        properties
                );
                worldSettingRepository.flush();
                appliedTarget = currentTarget;
            }
            Set<UUID> markedRootMoveDecisionIds = new HashSet<>();
            for (WorldSettingCandidate candidate : targetCandidates) {
                if (shouldApplyRootPropertyMoves(candidate, rootMoveDecisionIds)
                        && markedRootMoveDecisionIds.add(
                        candidate.getComparisonDecision().getId()
                )) {
                    candidate.getComparisonDecision().markRootPropertyMovesApplied(
                            appliedTarget.getVersion()
                    );
                }
            }
            for (WorldSettingCandidate candidate : targetCandidates) {
                appliedTargetsByCandidateId.put(candidate.getId(), appliedTarget);
            }
        }

        for (WorldSettingCandidate candidate : candidates) {
            WorldSettingCandidateGroupConfirmRequest.Decision decision = decisionsById.get(candidate.getId());
            if (decision.operation() == WorldSettingOperation.EXCLUDE) {
                candidate.dismiss(decision.reviewNote(), work.getMember());
            } else {
                candidate.confirm(
                        decision.operation(),
                        decision.category(),
                        decision.subjectName(),
                        decision.scopeName(),
                        decision.settingName(),
                        decision.value(),
                        decision.reviewNote(),
                        work.getMember(),
                        appliedTargetsByCandidateId.get(candidate.getId())
                );
            }
        }
        worldSettingCandidateRepository.flush();
        WorldSetting responseTarget = singleAppliedTarget(appliedTargetsByCandidateId.values());
        return WorldSettingCandidateGroupConfirmResult.confirmed(
                worldSettingMapper.toCandidateGroupActionResponse(
                        selectedGroupKey,
                        candidates,
                        responseTarget
                )
        );
    }

    @Override
    @Transactional
    public WorldSettingCandidateGroupActionResponse dismissCandidateGroup(
            Long memberId,
            UUID workId,
            WorldSettingCandidateGroupDismissRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        validateBatch(work, request.batchId());
        Set<UUID> candidateIds = new HashSet<>(request.candidateIds());
        if (candidateIds.size() != request.candidateIds().size()) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_SELECTION_INVALID);
        }
        List<WorldSettingCandidate> candidates = worldSettingCandidateRepository
                .findAllByIdsAndBatchForUpdate(work.getId(), request.batchId(), candidateIds);
        validateRequestedCandidates(candidates, candidateIds.size());
        validateCompleteComparisonDecisionMembership(candidates, candidateIds);
        String selectedGroupKey = validateSameCandidateGroup(candidates);
        disableRootPropertyMoves(candidates);
        for (WorldSettingCandidate candidate : candidates) {
            candidate.dismiss(request.reviewNote(), work.getMember());
        }
        worldSettingCandidateRepository.flush();
        return worldSettingMapper.toCandidateGroupActionResponse(selectedGroupKey, candidates, null);
    }

    private void enqueueRecomparisonJobIfAbsent(Long memberId, WorldSettingCandidate candidate) {
        AnalysisJob activeJob = analysisJobRepository
                .findFirstByWorldSettingCandidateIdAndStatusInOrderByCreatedAtDesc(
                        candidate.getId(),
                        List.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.RUNNING)
                )
                .orElse(null);
        if (activeJob != null) {
            if (activeJob.getStatus() == AnalysisJobStatus.RUNNING) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
            }
            return;
        }
        aiTokenService.ensureComparisonCanStart(memberId);
        analysisJobRepository.save(AnalysisJob.createWorldSettingComparison(candidate));
    }

    private void validateComparisonReadyAndOperation(
            WorldSettingCandidate candidate,
            WorldSettingCandidateConfirmRequest request
    ) {
        if (candidate.getComparisonStatus() != WorldSettingComparisonStatus.COMPLETED) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_NOT_READY);
        }
        if (request.operation() == WorldSettingOperation.EXCLUDE) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_OPERATION_INVALID);
        }
        if (candidate.getConsolidationStatus() == WorldSettingConsolidationStatus.CONFLICT
                && !Boolean.TRUE.equals(request.conflictResolved())) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_CONFLICT_UNRESOLVED);
        }
    }

    private boolean matchesComparedIdentity(
            WorldSettingCandidate candidate,
            WorldSetting comparedTarget,
            WorldSettingCandidateConfirmRequest request
    ) {
        WorldSettingCategory comparedCategory = comparedTarget == null
                ? candidate.getCategory()
                : comparedTarget.getCategory();
        String comparedSubjectName = comparedTarget == null
                ? candidate.getEffectiveSubjectName()
                : comparedTarget.getSubjectName();
        return comparedCategory == request.category()
                && sameName(comparedSubjectName, request.subjectName())
                && sameName(candidate.getProposedScopeName(), request.scopeName())
                && sameName(candidate.getProposedSettingName(), request.settingName());
    }

    private boolean isCurrentPropertyCompatible(
            WorldSettingCandidate candidate,
            WorldSetting currentTarget,
            WorldSettingCandidateConfirmRequest request
    ) {
        if (currentTarget.hasPathConflict(request.scopeName(), request.settingName())) {
            return false;
        }
        boolean propertyExists = currentTarget.hasProperty(request.scopeName(), request.settingName());
        if (request.operation() == WorldSettingOperation.ADD && propertyExists) {
            return false;
        }
        if ((request.operation() == WorldSettingOperation.UPDATE
                || request.operation() == WorldSettingOperation.MERGE)
                && !propertyExists) {
            return false;
        }

        String currentValue = currentTarget.getPropertyValue(request.scopeName(), request.settingName());
        boolean alreadyFinal = Objects.equals(currentValue, normalizeValue(request.value()));
        boolean comparisonStillCurrent = Objects.equals(currentValue, candidate.getBeforeValue());
        return alreadyFinal || comparisonStillCurrent;
    }

    private String normalizeValue(String value) {
        return WorldSettingNameNormalizer.displayName(value);
    }

    private boolean sameName(String left, String right) {
        return Objects.equals(
                WorldSettingNameNormalizer.duplicateKey(left),
                WorldSettingNameNormalizer.duplicateKey(right)
        );
    }

    private WorldSettingCandidateConfirmResult markRecomparisonRequired(WorldSettingCandidate candidate) {
        candidate.markRecomparisonRequired();
        worldSettingCandidateRepository.flush();
        return WorldSettingCandidateConfirmResult.recomparisonRequired(
                worldSettingMapper.toCandidateResponse(candidate)
        );
    }

    private void validateBatch(Work work, UUID batchId) {
        uploadBatchRepository.findByIdAndWorkId(batchId, work.getId())
                .orElseThrow(() -> new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_BATCH_NOT_FOUND));
    }

    private WorldSettingCandidate getCandidateForUpdate(UUID candidateId, UUID workId) {
        return worldSettingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)
                .orElseThrow(() -> new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_NOT_FOUND));
    }

    private Map<UUID, WorldSettingCandidateGroupConfirmRequest.Decision> decisionsById(
            List<WorldSettingCandidateGroupConfirmRequest.Decision> decisions
    ) {
        Map<UUID, WorldSettingCandidateGroupConfirmRequest.Decision> result = new LinkedHashMap<>();
        for (WorldSettingCandidateGroupConfirmRequest.Decision decision : decisions) {
            if (result.put(decision.candidateId(), decision) != null) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_SELECTION_INVALID);
            }
        }
        return result;
    }

    private void validateRequestedCandidates(List<WorldSettingCandidate> candidates, int requestedCount) {
        if (candidates.size() != requestedCount) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_NOT_FOUND);
        }
    }

    private void requireSingletonComparisonDecision(WorldSettingCandidate candidate) {
        if (candidate.getComparisonDecision() == null) {
            return;
        }
        if (!candidate.getComparisonDecision()
                .getExistingRootPropertyMoveSnapshots()
                .isEmpty()) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_SELECTION_INVALID);
        }
        long sourceCount = comparisonDecisionSourceRepository.countByComparisonDecisionId(
                candidate.getComparisonDecision().getId()
        );
        if (sourceCount != 1L) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_SELECTION_INVALID);
        }
    }

    private void validateCompleteComparisonDecisionMembership(
            List<WorldSettingCandidate> candidates,
            Set<UUID> requestedCandidateIds
    ) {
        Map<UUID, UUID> currentDecisionIdsByCandidateId = new LinkedHashMap<>();
        for (WorldSettingCandidate candidate : candidates) {
            currentDecisionIdsByCandidateId.put(
                    candidate.getId(),
                    candidate.getComparisonDecision() == null
                            ? null
                            : candidate.getComparisonDecision().getId()
            );
        }
        Set<UUID> decisionIds = candidates.stream()
                .map(WorldSettingCandidate::getComparisonDecision)
                .filter(Objects::nonNull)
                .map(decision -> decision.getId())
                .collect(java.util.stream.Collectors.toSet());
        if (decisionIds.isEmpty()) {
            return;
        }
        Map<UUID, Set<UUID>> sourceCandidateIdsByDecisionId = new LinkedHashMap<>();
        for (WorldSettingComparisonDecisionSource source
                : comparisonDecisionSourceRepository.findAllByComparisonDecisionIdIn(decisionIds)) {
            UUID decisionId = source.getComparisonDecision().getId();
            UUID sourceCandidateId = source.getCandidate().getId();
            if (!Objects.equals(
                    decisionId,
                    currentDecisionIdsByCandidateId.get(sourceCandidateId)
            )) {
                throw new AppException(
                        WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_SELECTION_INVALID
                );
            }
            sourceCandidateIdsByDecisionId
                    .computeIfAbsent(
                            decisionId,
                            ignored -> new HashSet<>()
                    )
                    .add(sourceCandidateId);
        }
        for (WorldSettingCandidate candidate : candidates) {
            if (candidate.getComparisonDecision() == null) {
                continue;
            }
            Set<UUID> sourceCandidateIds = sourceCandidateIdsByDecisionId.get(
                    candidate.getComparisonDecision().getId()
            );
            if (sourceCandidateIds == null
                    || !sourceCandidateIds.contains(candidate.getId())
                    || !requestedCandidateIds.containsAll(sourceCandidateIds)) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_SELECTION_INVALID);
            }
        }
    }

    private String validateSameCandidateGroup(List<WorldSettingCandidate> candidates) {
        Set<String> groupKeys = candidates.stream().map(this::groupKey).collect(java.util.stream.Collectors.toSet());
        if (groupKeys.size() != 1) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_GROUP_INVALID);
        }
        return groupKeys.iterator().next();
    }

    private void validateScopeReviewDecisionDrafts(
            List<WorldSettingCandidate> candidates,
            Map<UUID, WorldSettingCandidateGroupConfirmRequest.Decision> decisionsById
    ) {
        for (WorldSettingCandidate candidate : candidates) {
            if (candidate.getSuggestedOperation() != WorldSettingSuggestedOperation.REVIEW_REQUIRED) {
                continue;
            }
            WorldSettingCandidateGroupConfirmRequest.Decision decision = decisionsById.get(candidate.getId());
            if (!candidate.finalDecisionMatches(
                    decision.operation(),
                    decision.category(),
                    decision.subjectName(),
                    decision.scopeName(),
                    decision.settingName(),
                    decision.value()
            )) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_OPERATION_INVALID);
            }
        }
    }

    private void validateResolvedConflicts(
            List<WorldSettingCandidate> candidates,
            Map<UUID, WorldSettingCandidateGroupConfirmRequest.Decision> decisionsById
    ) {
        for (WorldSettingCandidate candidate : candidates) {
            WorldSettingCandidateGroupConfirmRequest.Decision decision = decisionsById.get(candidate.getId());
            if (candidate.getConsolidationStatus() == WorldSettingConsolidationStatus.CONFLICT
                    && decision.operation() != WorldSettingOperation.EXCLUDE
                    && !Boolean.TRUE.equals(decision.conflictResolved())) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_CONFLICT_UNRESOLVED);
            }
        }
    }

    private void validateDistinctSettingNames(
            List<WorldSettingCandidate> candidates,
            Map<UUID, WorldSettingCandidateGroupConfirmRequest.Decision> decisionsById,
            Set<UUID> rootMoveDecisionIds
    ) {
        Map<String, WorldSettingCandidate> candidateBySettingPath = new LinkedHashMap<>();
        for (WorldSettingCandidate candidate : candidates) {
            WorldSettingCandidateGroupConfirmRequest.Decision decision = decisionsById.get(
                    candidate.getId()
            );
            if (decision.operation() == WorldSettingOperation.EXCLUDE) {
                continue;
            }
            String finalTargetPath = groupKey(decision.category(), decision.subjectName())
                    + "|"
                    + propertyPathKey(decision.scopeName(), decision.settingName());
            WorldSettingCandidate existing = candidateBySettingPath.putIfAbsent(
                    finalTargetPath,
                    candidate
            );
            if (existing != null && !sharesSameFinalComparisonDecision(
                    existing,
                    candidate,
                    decisionsById
            )) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_SETTING_NAME_DUPLICATED);
            }
        }

        Map<String, WorldSettingCandidate> candidateByRootMovePath = new LinkedHashMap<>();
        for (WorldSettingCandidate candidate : candidates) {
            WorldSettingCandidateGroupConfirmRequest.Decision decision = decisionsById.get(
                    candidate.getId()
            );
            if (!shouldApplyRootPropertyMoves(candidate, rootMoveDecisionIds)) {
                continue;
            }
            for (ExistingRootPropertyMoveSnapshot snapshot
                    : candidate.getComparisonDecision().getExistingRootPropertyMoveSnapshots()) {
                String targetPath = groupKey(decision.category(), decision.subjectName())
                        + "|"
                        + propertyPathKey(decision.scopeName(), snapshot.settingName());
                if (candidateBySettingPath.containsKey(targetPath)) {
                    throw new AppException(
                            WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_SETTING_NAME_DUPLICATED
                    );
                }
                WorldSettingCandidate existing = candidateByRootMovePath.putIfAbsent(
                        targetPath,
                        candidate
                );
                if (existing != null && !sameComparisonDecision(existing, candidate)) {
                    throw new AppException(
                            WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_SETTING_NAME_DUPLICATED
                    );
                }
            }
        }
    }

    private boolean sameComparisonDecision(
            WorldSettingCandidate first,
            WorldSettingCandidate second
    ) {
        return first.getComparisonDecision() != null
                && second.getComparisonDecision() != null
                && first.getComparisonDecision().getId().equals(
                second.getComparisonDecision().getId()
        );
    }

    private boolean sharesSameFinalComparisonDecision(
            WorldSettingCandidate first,
            WorldSettingCandidate second,
            Map<UUID, WorldSettingCandidateGroupConfirmRequest.Decision> decisionsById
    ) {
        if (first.getComparisonDecision() == null
                || second.getComparisonDecision() == null
                || !first.getComparisonDecision().getId().equals(
                second.getComparisonDecision().getId()
        )) {
            return false;
        }
        WorldSettingCandidateGroupConfirmRequest.Decision firstDecision = decisionsById.get(
                first.getId()
        );
        WorldSettingCandidateGroupConfirmRequest.Decision secondDecision = decisionsById.get(
                second.getId()
        );
        return firstDecision.operation() == secondDecision.operation()
                && firstDecision.category() == secondDecision.category()
                && sameName(firstDecision.subjectName(), secondDecision.subjectName())
                && sameName(firstDecision.scopeName(), secondDecision.scopeName())
                && sameName(firstDecision.settingName(), secondDecision.settingName())
                && Objects.equals(
                normalizeValue(firstDecision.value()),
                normalizeValue(secondDecision.value())
        );
    }

    private WorldSetting singleAppliedTarget(Iterable<WorldSetting> targets) {
        WorldSetting singleTarget = null;
        for (WorldSetting target : targets) {
            if (singleTarget != null && !singleTarget.getId().equals(target.getId())) {
                return null;
            }
            singleTarget = target;
        }
        return singleTarget;
    }

    private Map<UUID, WorldSettingRecomparisonReason> propertyConflicts(
            List<WorldSettingCandidate> candidates,
            Map<UUID, WorldSettingCandidateGroupConfirmRequest.Decision> decisionsById,
            WorldSetting currentTarget,
            Set<UUID> rootMoveDecisionIds
    ) {
        Map<UUID, WorldSettingRecomparisonReason> conflicts = new LinkedHashMap<>();
        for (WorldSettingCandidate candidate : candidates) {
            WorldSettingCandidateGroupConfirmRequest.Decision decision = decisionsById.get(candidate.getId());
            if (isAuthorEditedDecision(candidate, decision)) {
                continue;
            }
            if (currentTarget == null) {
                if (decision.operation() != WorldSettingOperation.ADD) {
                    conflicts.put(candidate.getId(), WorldSettingRecomparisonReason.PROPERTY_REMOVED);
                }
                continue;
            }
            WorldSettingRecomparisonReason rootMoveConflict = rootPropertyMoveConflict(
                    candidate,
                    decision,
                    currentTarget,
                    rootMoveDecisionIds
            );
            if (rootMoveConflict != null) {
                conflicts.put(candidate.getId(), rootMoveConflict);
                continue;
            }
            if (currentTarget.hasPathConflict(decision.scopeName(), decision.settingName())) {
                conflicts.put(candidate.getId(), WorldSettingRecomparisonReason.PROPERTY_PATH_CONFLICT);
                continue;
            }
            String currentValue = currentTarget.getPropertyValue(
                    decision.scopeName(),
                    decision.settingName()
            );
            if (Objects.equals(currentValue, normalizeValue(decision.value()))) {
                continue;
            }
            if (decision.operation() == WorldSettingOperation.ADD) {
                if (currentValue != null) {
                    conflicts.put(candidate.getId(), WorldSettingRecomparisonReason.PROPERTY_ADDED);
                }
                continue;
            }
            if (currentValue == null) {
                conflicts.put(candidate.getId(), WorldSettingRecomparisonReason.PROPERTY_REMOVED);
            } else if (!Objects.equals(currentValue, candidate.getBeforeValue())) {
                conflicts.put(candidate.getId(), WorldSettingRecomparisonReason.PROPERTY_CHANGED);
            }
        }
        return conflicts;
    }

    private WorldSettingRecomparisonReason rootPropertyMoveConflict(
            WorldSettingCandidate candidate,
            WorldSettingCandidateGroupConfirmRequest.Decision decision,
            WorldSetting currentTarget,
            Set<UUID> rootMoveDecisionIds
    ) {
        if (!shouldApplyRootPropertyMoves(candidate, rootMoveDecisionIds)) {
            return null;
        }
        for (ExistingRootPropertyMoveSnapshot snapshot
                : candidate.getComparisonDecision().getExistingRootPropertyMoveSnapshots()) {
            WorldSetting.StoredPropertyPath sourcePath = currentTarget.getStoredPropertyPath(
                    null,
                    snapshot.settingName()
            );
            if (sourcePath == null) {
                return WorldSettingRecomparisonReason.PROPERTY_REMOVED;
            }
            String currentValue = currentTarget.getPropertyValue(
                    null,
                    sourcePath.settingName()
            );
            if (!Objects.equals(currentValue, normalizeValue(snapshot.beforeValue()))) {
                return WorldSettingRecomparisonReason.PROPERTY_CHANGED;
            }
            if (currentTarget.hasPathConflict(decision.scopeName(), sourcePath.settingName())) {
                return WorldSettingRecomparisonReason.PROPERTY_PATH_CONFLICT;
            }
            if (currentTarget.hasProperty(decision.scopeName(), sourcePath.settingName())) {
                return WorldSettingRecomparisonReason.PROPERTY_ADDED;
            }
        }
        return null;
    }

    private WorldSettingRecomparisonReason targetConflict(
            List<WorldSettingCandidate> candidates,
            Map<UUID, WorldSettingCandidateGroupConfirmRequest.Decision> decisionsById,
            WorldSetting currentTarget
    ) {
        for (WorldSettingCandidate candidate : candidates) {
            WorldSettingCandidateGroupConfirmRequest.Decision decision = decisionsById.get(candidate.getId());
            if (isAuthorEditedDecision(candidate, decision)) {
                continue;
            }
            WorldSetting comparedTarget = candidate.getTargetWorldSetting();
            if (comparedTarget == null && currentTarget != null) {
                return WorldSettingRecomparisonReason.TARGET_CREATED;
            }
            if (comparedTarget != null && currentTarget == null) {
                return WorldSettingRecomparisonReason.TARGET_MISSING;
            }
            if (comparedTarget != null && !comparedTarget.getId().equals(currentTarget.getId())) {
                return WorldSettingRecomparisonReason.TARGET_IDENTITY_CHANGED;
            }
        }
        return null;
    }

    private void validateAuthorEditedDecisions(
            List<WorldSettingCandidate> candidates,
            Map<UUID, WorldSettingCandidateGroupConfirmRequest.Decision> decisionsById,
            WorldSetting currentTarget
    ) {
        for (WorldSettingCandidate candidate : candidates) {
            WorldSettingCandidateGroupConfirmRequest.Decision decision = decisionsById.get(candidate.getId());
            if (isAuthorEditedDecision(candidate, decision)) {
                validateAuthorDecisionCompatible(
                        currentTarget,
                        decision.operation(),
                        decision.scopeName(),
                        decision.settingName()
                );
            }
        }
    }

    private void validateAuthorDecisionCompatible(
            WorldSetting currentTarget,
            WorldSettingOperation operation,
            String scopeName,
            String settingName
    ) {
        if (currentTarget == null) {
            if (operation != WorldSettingOperation.ADD) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_UPDATE_PATH_NOT_FOUND);
            }
            return;
        }
        if (currentTarget.hasPathConflict(scopeName, settingName)) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTY_PATH_CONFLICT);
        }
        boolean propertyExists = currentTarget.hasProperty(scopeName, settingName);
        if (operation == WorldSettingOperation.ADD && propertyExists) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_ADD_PATH_DUPLICATED);
        }
        if ((operation == WorldSettingOperation.UPDATE || operation == WorldSettingOperation.MERGE)
                && !propertyExists) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_UPDATE_PATH_NOT_FOUND);
        }
    }

    private boolean isAuthorEditedDecision(
            WorldSettingCandidate candidate,
            WorldSettingCandidateGroupConfirmRequest.Decision decision
    ) {
        return isAuthorEditedDecision(
                candidate,
                decision.operation(),
                decision.category(),
                decision.subjectName(),
                decision.scopeName(),
                decision.settingName(),
                decision.value()
        );
    }

    private boolean isAuthorEditedDecision(
            WorldSettingCandidate candidate,
            WorldSettingCandidateConfirmRequest request
    ) {
        return isAuthorEditedDecision(
                candidate,
                request.operation(),
                request.category(),
                request.subjectName(),
                request.scopeName(),
                request.settingName(),
                request.value()
        );
    }

    private boolean isAuthorEditedDecision(
            WorldSettingCandidate candidate,
            WorldSettingOperation operation,
            WorldSettingCategory category,
            String subjectName,
            String scopeName,
            String settingName,
            String value
    ) {
        WorldSetting comparedTarget = candidate.getTargetWorldSetting();
        WorldSettingCategory comparedCategory = comparedTarget == null
                ? candidate.getCategory()
                : comparedTarget.getCategory();
        String comparedSubjectName = comparedTarget == null
                ? candidate.getEffectiveSubjectName()
                : comparedTarget.getSubjectName();
        String comparedScopeName = candidate.getProposedSettingName() == null
                ? candidate.getScopeName()
                : candidate.getProposedScopeName();
        String comparedSettingName = candidate.getProposedSettingName() == null
                ? candidate.getSettingName()
                : candidate.getProposedSettingName();
        String comparedValue = candidate.getProposedValue() == null
                ? candidate.getExtractedValue()
                : candidate.getProposedValue();
        return !candidate.suggestedOperationMatches(operation)
                || category != comparedCategory
                || !sameName(subjectName, comparedSubjectName)
                || !sameName(scopeName, comparedScopeName)
                || !sameName(settingName, comparedSettingName)
                || !Objects.equals(normalizeValue(value), normalizeValue(comparedValue));
    }

    private Set<UUID> rootMoveDecisionIdsToApply(
            List<WorldSettingCandidate> candidates,
            Map<UUID, WorldSettingCandidateGroupConfirmRequest.Decision> decisionsById
    ) {
        Map<UUID, List<WorldSettingCandidate>> candidatesByDecisionId = new LinkedHashMap<>();
        for (WorldSettingCandidate candidate : candidates) {
            if (candidate.getComparisonDecision() == null
                    || candidate.getComparisonDecision()
                            .getExistingRootPropertyMoveSnapshots()
                            .isEmpty()
                    || candidate.getComparisonDecision().isRootPropertyMovesDisabled()) {
                continue;
            }
            candidatesByDecisionId.computeIfAbsent(
                    candidate.getComparisonDecision().getId(),
                    ignored -> new ArrayList<>()
            ).add(candidate);
        }
        Set<UUID> result = new HashSet<>();
        for (Map.Entry<UUID, List<WorldSettingCandidate>> entry
                : candidatesByDecisionId.entrySet()) {
            boolean acceptedUnchanged = entry.getValue().stream().allMatch(candidate -> {
                WorldSettingCandidateGroupConfirmRequest.Decision decision = decisionsById.get(
                        candidate.getId()
                );
                return decision.operation() != WorldSettingOperation.EXCLUDE
                        && !isAuthorEditedDecision(candidate, decision);
            });
            if (acceptedUnchanged) {
                result.add(entry.getKey());
            } else {
                entry.getValue().getFirst()
                        .getComparisonDecision()
                        .disableRootPropertyMoves();
            }
        }
        return Set.copyOf(result);
    }

    private void disableRootPropertyMoves(List<WorldSettingCandidate> candidates) {
        Set<UUID> disabledDecisionIds = new HashSet<>();
        for (WorldSettingCandidate candidate : candidates) {
            WorldSettingComparisonDecision decision = candidate.getComparisonDecision();
            if (decision != null && disabledDecisionIds.add(decision.getId())) {
                decision.disableRootPropertyMoves();
            }
        }
    }

    private boolean shouldApplyRootPropertyMoves(
            WorldSettingCandidate candidate,
            Set<UUID> rootMoveDecisionIds
    ) {
        WorldSettingComparisonDecision comparisonDecision = candidate.getComparisonDecision();
        return comparisonDecision != null
                && rootMoveDecisionIds.contains(comparisonDecision.getId());
    }

    private WorldSettingCandidateGroupConfirmResult markGroupRecomparisonRequired(
            Work work,
            UUID batchId,
            String selectedGroupKey,
            WorldSettingRecomparisonReason reason
    ) {
        List<WorldSettingCandidate> affectedCandidates = worldSettingCandidateRepository
                .findAllByBatchAndReviewStatusForUpdate(
                        work.getId(),
                        batchId,
                        WorldSettingReviewStatus.PENDING_REVIEW
                ).stream()
                .filter(candidate -> groupKey(candidate).equals(selectedGroupKey))
                .toList();
        affectedCandidates.forEach(candidate -> candidate.markRecomparisonRequired(reason.getMessage()));
        worldSettingCandidateRepository.flush();
        return WorldSettingCandidateGroupConfirmResult.recomparisonRequired(
                WorldSettingRecomparisonScope.GROUP,
                reason,
                affectedCandidates.stream().map(WorldSettingCandidate::getId).toList()
        );
    }

    private String groupKey(WorldSettingCandidate candidate) {
        if (candidate.isPendingReview() && candidate.getFinalOperation() != null) {
            return groupKey(candidate.getFinalCategory(), candidate.getFinalSubjectName());
        }
        if (candidate.getComparisonDecision() != null) {
            return groupKey(
                    candidate.getComparisonDecision().getComparisonBatch().getCategory(),
                    candidate.getComparisonDecision().getCanonicalSubjectName()
            );
        }
        return groupKey(candidate.getEffectiveCategory(), candidate.getEffectiveSubjectName());
    }

    private String groupKey(WorldSettingCategory category, String subjectName) {
        return category.name() + "|" + WorldSettingNameNormalizer.duplicateKey(subjectName);
    }

    private String propertyPathKey(String scopeName, String settingName) {
        return Objects.toString(WorldSettingNameNormalizer.duplicateKey(scopeName), "<root>")
                + "|"
                + WorldSettingNameNormalizer.duplicateKey(settingName);
    }
}
