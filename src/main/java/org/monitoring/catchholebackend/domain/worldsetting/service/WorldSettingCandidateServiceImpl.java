package org.monitoring.catchholebackend.domain.worldsetting.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobEpisodeRange;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateGroupConfirmRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateGroupDismissRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateConfirmRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateDismissRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateUpdateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateGroupActionResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateGroupResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateListResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateResponse;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.exception.WorldSettingErrorCode;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingMapper;
import org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingNameNormalizer;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateBatchCounts;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingConsolidationStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingRecomparisonReason;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingRecomparisonScope;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
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
    private final WorldSettingMapper worldSettingMapper;
    private final AiTokenService aiTokenService;

    @Override
    public WorldSettingCandidateListResponse getCandidates(
            Long memberId,
            UUID workId,
            UUID batchId,
            WorldSettingReviewStatus reviewStatus,
            WorldSettingCategory category,
            WorldSettingOperation operation,
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
                operation
        );
        WorldSettingCandidateBatchCounts counts = worldSettingCandidateRepository.countReviewSummary(
                work.getId(),
                batchId,
                WorldSettingReviewStatus.PENDING_REVIEW,
                WorldSettingComparisonStatus.PENDING,
                WorldSettingComparisonStatus.PROCESSING,
                WorldSettingComparisonStatus.FAILED,
                WorldSettingComparisonStatus.RECOMPARISON_REQUIRED,
                WorldSettingComparisonStatus.COMPLETED,
                WorldSettingConsolidationStatus.CONFLICT
        );
        AnalysisJobEpisodeRange episodeRange =
                analysisJobRepository.findEpisodeRangeByWorkIdAndBatchId(work.getId(), batchId);
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
                counts.getFailedComparisonCount(),
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
    public WorldSettingCandidateResponse updateCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId,
            WorldSettingCandidateUpdateRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        WorldSettingCandidate candidate = getCandidateForUpdate(candidateId, work.getId());
        candidate.updateExtractionIdentity(request.category(), request.subjectName(), request.settingName());
        enqueueRecomparisonJobIfAbsent(memberId, candidate);
        worldSettingCandidateRepository.flush();
        return worldSettingMapper.toCandidateResponse(candidate);
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
    public WorldSettingCandidateConfirmResult confirmCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId,
            WorldSettingCandidateConfirmRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        WorldSettingCandidate candidate = getCandidateForUpdate(candidateId, work.getId());

        if (candidate.getReviewStatus() == WorldSettingReviewStatus.CONFIRMED) {
            candidate.confirm(
                    request.operation(),
                    request.category(),
                    request.subjectName(),
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
            currentTarget.applyProperty(request.settingName(), request.value());
            worldSettingRepository.flush();
            appliedTarget = currentTarget;
        }

        candidate.confirm(
                request.operation(),
                request.category(),
                request.subjectName(),
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
                            decision.settingName(),
                            decision.value(),
                            decision.reviewNote(),
                            work.getMember(),
                            candidate.getTargetWorldSetting()
                    );
                }
            }
            WorldSetting appliedTarget = candidates.stream()
                    .map(WorldSettingCandidate::getTargetWorldSetting)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
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

        validateResolvedConflicts(candidates, decisionsById);
        validateDecisionsMatchGroup(candidates, decisionsById, selectedGroupKey);
        validateDistinctSettingNames(request.candidates());
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

        Set<UUID> comparedTargetIds = new HashSet<>();
        boolean hasCandidateWithoutTarget = false;
        for (WorldSettingCandidate candidate : appliedCandidates) {
            if (candidate.getTargetWorldSetting() == null) {
                hasCandidateWithoutTarget = true;
            } else {
                comparedTargetIds.add(candidate.getTargetWorldSetting().getId());
            }
        }
        if (comparedTargetIds.size() > 1 || (hasCandidateWithoutTarget && !comparedTargetIds.isEmpty())) {
            return markGroupRecomparisonRequired(
                    work,
                    request.batchId(),
                    selectedGroupKey,
                    WorldSettingRecomparisonReason.TARGET_IDENTITY_CHANGED
            );
        }

        WorldSettingCandidateGroupConfirmRequest.Decision representativeDecision =
                decisionsById.get(appliedCandidates.getFirst().getId());
        WorldSetting currentTarget;
        boolean createsTarget = comparedTargetIds.isEmpty();
        if (createsTarget) {
            currentTarget = worldSettingRepository.findByIdentityForUpdate(
                    work.getId(),
                    representativeDecision.category(),
                    WorldSettingNameNormalizer.duplicateKey(representativeDecision.subjectName())
            ).orElse(null);
            if (currentTarget != null) {
                return markGroupRecomparisonRequired(
                        work,
                        request.batchId(),
                        selectedGroupKey,
                        WorldSettingRecomparisonReason.TARGET_CREATED
                );
            }
        } else {
            UUID comparedTargetId = comparedTargetIds.iterator().next();
            currentTarget = worldSettingRepository.findByIdAndWorkIdForUpdate(
                    comparedTargetId,
                    work.getId()
            ).orElse(null);
            if (currentTarget == null) {
                return markGroupRecomparisonRequired(
                        work,
                        request.batchId(),
                        selectedGroupKey,
                        WorldSettingRecomparisonReason.TARGET_MISSING
                );
            }
            if (currentTarget.getCategory() != representativeDecision.category()
                    || !sameName(currentTarget.getSubjectName(), representativeDecision.subjectName())) {
                return markGroupRecomparisonRequired(
                        work,
                        request.batchId(),
                        selectedGroupKey,
                        WorldSettingRecomparisonReason.TARGET_IDENTITY_CHANGED
                );
            }
        }

        Map<UUID, WorldSettingRecomparisonReason> conflicts = propertyConflicts(
                appliedCandidates,
                decisionsById,
                currentTarget
        );
        if (!conflicts.isEmpty()) {
            WorldSettingRecomparisonReason reason = conflicts.values().iterator().next();
            for (WorldSettingCandidate candidate : appliedCandidates) {
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

        Map<String, String> properties = new LinkedHashMap<>();
        for (WorldSettingCandidate candidate : appliedCandidates) {
            WorldSettingCandidateGroupConfirmRequest.Decision decision = decisionsById.get(candidate.getId());
            properties.put(decision.settingName(), decision.value());
        }
        WorldSetting appliedTarget;
        if (currentTarget == null) {
            appliedTarget = worldSettingRepository.saveAndFlush(WorldSetting.create(
                    work,
                    representativeDecision.category(),
                    representativeDecision.subjectName(),
                    properties
            ));
        } else {
            currentTarget.applyProperties(properties);
            worldSettingRepository.flush();
            appliedTarget = currentTarget;
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
                        decision.settingName(),
                        decision.value(),
                        decision.reviewNote(),
                        work.getMember(),
                        appliedTarget
                );
            }
        }
        if (createsTarget) {
            markUnselectedCandidatesForRecomparison(
                    work,
                    request.batchId(),
                    selectedGroupKey,
                    decisionsById.keySet()
            );
        }
        worldSettingCandidateRepository.flush();
        return WorldSettingCandidateGroupConfirmResult.confirmed(
                worldSettingMapper.toCandidateGroupActionResponse(
                        selectedGroupKey,
                        candidates,
                        appliedTarget
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
        String selectedGroupKey = validateSameCandidateGroup(candidates);
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
        aiTokenService.ensureAnalysisCanStart(memberId);
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
                ? candidate.getSubjectName()
                : comparedTarget.getSubjectName();
        return comparedCategory == request.category()
                && sameName(comparedSubjectName, request.subjectName())
                && sameName(candidate.getProposedSettingName(), request.settingName());
    }

    private boolean isCurrentPropertyCompatible(
            WorldSettingCandidate candidate,
            WorldSetting currentTarget,
            WorldSettingCandidateConfirmRequest request
    ) {
        boolean propertyExists = currentTarget.hasProperty(request.settingName());
        if (request.operation() == WorldSettingOperation.ADD && propertyExists) {
            return false;
        }
        if ((request.operation() == WorldSettingOperation.UPDATE
                || request.operation() == WorldSettingOperation.MERGE)
                && !propertyExists) {
            return false;
        }

        String currentValue = currentTarget.getPropertyValue(request.settingName());
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

    private String validateSameCandidateGroup(List<WorldSettingCandidate> candidates) {
        Set<String> groupKeys = candidates.stream().map(this::groupKey).collect(java.util.stream.Collectors.toSet());
        if (groupKeys.size() != 1) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_GROUP_INVALID);
        }
        return groupKeys.iterator().next();
    }

    private void validateDecisionsMatchGroup(
            List<WorldSettingCandidate> candidates,
            Map<UUID, WorldSettingCandidateGroupConfirmRequest.Decision> decisionsById,
            String selectedGroupKey
    ) {
        for (WorldSettingCandidate candidate : candidates) {
            WorldSettingCandidateGroupConfirmRequest.Decision decision = decisionsById.get(candidate.getId());
            String decisionGroupKey = groupKey(decision.category(), decision.subjectName());
            if (!selectedGroupKey.equals(decisionGroupKey)) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_GROUP_INVALID);
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
            List<WorldSettingCandidateGroupConfirmRequest.Decision> decisions
    ) {
        Set<String> settingNames = new HashSet<>();
        for (WorldSettingCandidateGroupConfirmRequest.Decision decision : decisions) {
            if (decision.operation() == WorldSettingOperation.EXCLUDE) {
                continue;
            }
            if (!settingNames.add(WorldSettingNameNormalizer.duplicateKey(decision.settingName()))) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_SETTING_NAME_DUPLICATED);
            }
        }
    }

    private Map<UUID, WorldSettingRecomparisonReason> propertyConflicts(
            List<WorldSettingCandidate> candidates,
            Map<UUID, WorldSettingCandidateGroupConfirmRequest.Decision> decisionsById,
            WorldSetting currentTarget
    ) {
        Map<UUID, WorldSettingRecomparisonReason> conflicts = new LinkedHashMap<>();
        for (WorldSettingCandidate candidate : candidates) {
            WorldSettingCandidateGroupConfirmRequest.Decision decision = decisionsById.get(candidate.getId());
            if (!sameName(candidate.getProposedSettingName(), decision.settingName())) {
                conflicts.put(candidate.getId(), WorldSettingRecomparisonReason.PROPERTY_CHANGED);
                continue;
            }
            if (currentTarget == null) {
                if (decision.operation() != WorldSettingOperation.ADD) {
                    conflicts.put(candidate.getId(), WorldSettingRecomparisonReason.PROPERTY_REMOVED);
                }
                continue;
            }
            String currentValue = currentTarget.getPropertyValue(decision.settingName());
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

    private void markUnselectedCandidatesForRecomparison(
            Work work,
            UUID batchId,
            String selectedGroupKey,
            Set<UUID> selectedCandidateIds
    ) {
        worldSettingCandidateRepository.findAllByBatchAndReviewStatusForUpdate(
                        work.getId(),
                        batchId,
                        WorldSettingReviewStatus.PENDING_REVIEW
                ).stream()
                .filter(candidate -> !selectedCandidateIds.contains(candidate.getId()))
                .filter(candidate -> groupKey(candidate).equals(selectedGroupKey))
                .forEach(candidate -> candidate.markRecomparisonRequired(
                        WorldSettingRecomparisonReason.NEW_TARGET_PARTIALLY_CONFIRMED.getMessage()
                ));
    }

    private String groupKey(WorldSettingCandidate candidate) {
        WorldSetting target = candidate.getTargetWorldSetting();
        return groupKey(
                target == null ? candidate.getCategory() : target.getCategory(),
                target == null ? candidate.getSubjectName() : target.getSubjectName()
        );
    }

    private String groupKey(WorldSettingCategory category, String subjectName) {
        return category.name() + "|" + WorldSettingNameNormalizer.duplicateKey(subjectName);
    }
}
