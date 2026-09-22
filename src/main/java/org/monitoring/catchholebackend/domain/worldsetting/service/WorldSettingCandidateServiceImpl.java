package org.monitoring.catchholebackend.domain.worldsetting.service;

import org.monitoring.catchholebackend.domain.worldimage.service.AutomaticImageService;
import org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason;
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
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisAutomaticApplicationContributor;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobEpisodeRange;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
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
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSubjectResolutionType;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorldSettingCandidateServiceImpl implements WorldSettingCandidateService,
        AnalysisAutomaticApplicationContributor {

    private final WorkRepository workRepository;
    private final AnalysisRunStateService analysisRunStateService;
    private final UploadBatchRepository uploadBatchRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final WorldSettingRepository worldSettingRepository;
    private final AutomaticImageService automaticImages;
    private final WorldSettingCandidateRepository worldSettingCandidateRepository;
    private final WorldSettingComparisonDecisionSourceRepository comparisonDecisionSourceRepository;
    private final WorldSettingMapper worldSettingMapper;
    private final AiTokenService aiTokenService;
    private final WorldSettingAnalysisConfirmation analysisConfirmation;

    @Override
    public String applicationDomain() {
        return "worldSettings";
    }

    @Override
    @Transactional
    public void applyAutomatically(AnalysisJob job) {
        if (!job.isAutomaticReview() || !job.isOrderedProvisional()
                || job.getJobType() != AnalysisJobType.SETTING_EXTRACTION) {
            return;
        }
        List<WorldSettingCandidate> eligible = worldSettingCandidateRepository
                .findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(job.getId()).stream()
                .filter(this::isAutomaticCandidate)
                .sorted(org.monitoring.catchholebackend.domain.worldsetting.processor
                        .WorldSettingCandidateChronology.comparator())
                .toList();
        Set<UUID> eligibleIds = eligible.stream().map(WorldSettingCandidate::getId)
                .collect(java.util.stream.Collectors.toSet());
        Set<UUID> decisionIds = eligible.stream().map(WorldSettingCandidate::getComparisonDecision)
                .map(WorldSettingComparisonDecision::getId).collect(java.util.stream.Collectors.toSet());
        Map<UUID, Set<UUID>> sourcesByDecision = new LinkedHashMap<>();
        if (!decisionIds.isEmpty()) {
            for (WorldSettingComparisonDecisionSource source
                    : comparisonDecisionSourceRepository.findAllByComparisonDecisionIdIn(decisionIds)) {
                sourcesByDecision.computeIfAbsent(source.getComparisonDecision().getId(), ignored -> new HashSet<>())
                        .add(source.getCandidate().getId());
            }
        }
        Map<String, List<WorldSettingCandidate>> groups = new LinkedHashMap<>();
        for (WorldSettingCandidate candidate : eligible) {
            Set<UUID> members = sourcesByDecision.get(candidate.getComparisonDecision().getId());
            if (members == null || !members.contains(candidate.getId()) || !eligibleIds.containsAll(members)) {
                continue;
            }
            groups.computeIfAbsent(groupKey(candidate), ignored -> new ArrayList<>()).add(candidate);
        }
        for (List<WorldSettingCandidate> group : groups.values()) {
            WorldSettingCandidateGroupConfirmRequest request = new WorldSettingCandidateGroupConfirmRequest(
                    job.getBatch().getId(), group.stream().map(this::automaticDecision).toList());
            try {
                var result = confirmCandidateGroupCore(job.getWork(), request, true);
                if (result.recomparisonRequired()) {
                    group.stream().filter(candidate -> candidate.getAutomaticReviewHoldReason() == null)
                            .forEach(candidate -> candidate.recordAutomaticReviewHold(
                                    AutomaticReviewHoldReason.CURRENT_SETTING_CHANGED));
                }
            } catch (AppException exception) {
                // These are validation failures before writes; unknown/DB failures must roll back completion.
                if (!automaticReviewableError(exception)) {
                    throw exception;
                }
                var reason = exception.getResultCode() == WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_GROUP_INVALID
                        ? AutomaticReviewHoldReason.SUBJECT_CONFIRMATION_REQUIRED
                        : exception.getResultCode() == WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_ADD_PATH_DUPLICATED
                        || exception.getResultCode() == WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_UPDATE_PATH_NOT_FOUND
                        || exception.getResultCode() == WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_SETTING_NAME_DUPLICATED
                        ? AutomaticReviewHoldReason.SETTING_LOCATION_CONFLICT
                        : AutomaticReviewHoldReason.REVIEW_REQUIRED;
                group.forEach(candidate -> candidate.recordAutomaticReviewHold(reason));
            }
        }
    }

    private boolean isAutomaticCandidate(WorldSettingCandidate candidate) {
        return candidate.isPendingReview()
                && candidate.getComparisonStatus() == WorldSettingComparisonStatus.COMPLETED
                && candidate.getFinalOperation() == null
                && candidate.getConsolidationStatus() != WorldSettingConsolidationStatus.CONFLICT
                && candidate.getSuggestedOperation() != null
                && candidate.getSuggestedOperation() != WorldSettingSuggestedOperation.REVIEW_REQUIRED
                && candidate.getComparisonReviewReason() == null
                && (candidate.getSubjectResolutionType() == WorldSettingSubjectResolutionType.NEW
                    || candidate.getSubjectResolutionType() == WorldSettingSubjectResolutionType.EXISTING)
                && candidate.getComparisonDecision() != null
                && candidate.getComparisonDecision().getSuggestedOperation() == candidate.getSuggestedOperation()
                && candidate.getComparisonDecision().getConsolidationStatus() != WorldSettingConsolidationStatus.CONFLICT
                && !candidate.getComparisonDecision().isRootPropertyMovesDisabled();
    }

    private WorldSettingCandidateGroupConfirmRequest.Decision automaticDecision(WorldSettingCandidate candidate) {
        WorldSettingComparisonDecision proposal = candidate.getComparisonDecision();
        return new WorldSettingCandidateGroupConfirmRequest.Decision(candidate.getId(),
                WorldSettingOperation.valueOf(proposal.getSuggestedOperation().name()),
                proposal.getComparisonBatch().getCategory(), proposal.getCanonicalSubjectName(),
                proposal.getProposedScopeName(), proposal.getProposedSettingName(), proposal.getProposedValue(),
                false, null);
    }

    private boolean automaticReviewableError(AppException exception) {
        return Set.of(
                WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_SELECTION_INVALID,
                WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_GROUP_INVALID,
                WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_NOT_READY,
                WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT,
                WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_CONFLICT_UNRESOLVED,
                WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_OPERATION_INVALID,
                WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_SETTING_NAME_DUPLICATED,
                WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_ADD_PATH_DUPLICATED,
                WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_UPDATE_PATH_NOT_FOUND
        ).contains(exception.getResultCode());
    }

    private boolean automaticExclusionsRemainCurrent(List<WorldSettingCandidate> candidates) {
        List<WorldSettingCandidate> changes = candidates.stream()
                .filter(candidate -> candidate.getSuggestedOperation() != WorldSettingSuggestedOperation.EXCLUDE)
                .toList();
        Set<UUID> checkedDecisions = new HashSet<>();
        for (WorldSettingCandidate candidate : candidates) {
            WorldSettingComparisonDecision decision = candidate.getComparisonDecision();
            if (candidate.getSuggestedOperation() != WorldSettingSuggestedOperation.EXCLUDE) {
                continue;
            }
            if (decision.getMatchedPropertyName() == null || !checkedDecisions.add(decision.getId())) {
                continue;
            }
            if (decision.getBaseWorldSettingVersion() == null) {
                return false;
            }
            // 한 batch의 모든 결정은 같은 고정 snapshot을 비교한다. 원문 근거 순서와
            // batch 처리 순서는 다를 수 있으므로 더 이른 비교 version의 변경만 복원한다.
            List<WorldSettingCandidate> precedingChanges = changes.stream()
                    .filter(change -> !Objects.equals(change.getComparisonDecision().getComparisonBatch().getId(),
                            decision.getComparisonBatch().getId()))
                    .filter(change -> change.getComparisonDecision().getBaseWorldSettingVersion() != null
                            && change.getComparisonDecision().getBaseWorldSettingVersion()
                                    < decision.getBaseWorldSettingVersion())
                    .toList();
            Map<UUID, WorldSettingCandidateGroupConfirmRequest.Decision> precedingSelections = new LinkedHashMap<>();
            precedingChanges.forEach(change -> precedingSelections.put(change.getId(), automaticDecision(change)));
            WorldSetting expected = decision.getTargetWorldSetting();
            if (expected == null && decision.getProvisionalSubjectKey() != null) {
                UUID anchorId = UUID.fromString(decision.getProvisionalSubjectKey().substring("provisional-world:".length()));
                WorldSettingCandidate anchor = worldSettingCandidateRepository
                        .findByIdAndWorkId(anchorId, candidate.getWork().getId()).orElse(null);
                expected = anchor == null ? null : anchor.getTargetWorldSetting();
            }
            WorldSetting actual = worldSettingRepository.findByIdentityForUpdate(candidate.getWork().getId(),
                    candidate.getEffectiveCategory(),
                    WorldSettingNameNormalizer.duplicateKey(decision.getCanonicalSubjectName())).orElse(null);
            boolean newTargetInGroup = expected == null && actual == null
                    && decision.getProvisionalSubjectKey() != null
                    && precedingChanges.stream().anyMatch(earlier -> Objects.equals(
                            earlier.getComparisonDecision().getProvisionalSubjectKey(),
                            decision.getProvisionalSubjectKey()));
            if (!newTargetInGroup && (expected == null || actual == null
                    || !Objects.equals(expected.getId(), actual.getId()))) {
                return false;
            }
            // 실제 저장과 같은 검증을 거친 이전 batch의 변경만 사용한다. 오래된 실제값이나
            // 다른 임시 대상은 여전히 어떤 속성도 쓰기 전에 전체 그룹을 보류한다.
            List<WorldSetting.Property> properties;
            if (precedingChanges.isEmpty()) {
                properties = actual.getProperties();
            } else {
                var projected = analysisConfirmation.projectAutomatically(precedingChanges, precedingSelections, actual);
                if (projected.properties() == null) {
                    return false;
                }
                properties = projected.properties();
            }
            String matchedPath = propertyPathKey(decision.getMatchedScopeName(), decision.getMatchedPropertyName());
            String currentValue = properties.stream()
                    .filter(property -> propertyPathKey(property.scopeName(), property.settingName()).equals(matchedPath))
                    .map(WorldSetting.Property::value).findFirst().orElse(null);
            if (currentValue == null || !Objects.equals(currentValue, decision.getBeforeValue())) {
                return false;
            }
        }
        return true;
    }

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
                counts.canResumeTokenInterruptedComparisons(),
                counts.getRecomparisonRequiredCount(),
                counts.getConflictCandidateCount(),
                counts.getConfirmedCandidateCount(),
                counts.getDismissedCandidateCount(),
                counts.getDirectReviewCandidateCount(),
                counts.getProcessingCandidateCount(),
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
        validateReviewMutationAllowed(work, candidateIds);
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
        validateAutomaticApplicationNotPending(work, List.of(candidateId));
        WorldSettingCandidate candidate = getCandidateForUpdate(candidateId, work.getId());
        if (isOrdered(candidate)) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_ORDERED_JOB_RETRY_REQUIRED);
        }
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
        validateAutomaticApplicationNotPending(work,
                interruptedCandidates.stream().map(WorldSettingCandidate::getId).toList());
        if (interruptedCandidates.stream().anyMatch(this::isOrdered)) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_ORDERED_JOB_RETRY_REQUIRED);
        }
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
        analysisConfirmation.assertReviewSourceCurrent(candidate);
        validateReviewMutationAllowed(work, List.of(candidateId));
        if (candidate.getReviewStatus() == WorldSettingReviewStatus.DISMISSED) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT);
        }

        validateComparisonReadyAndOperation(candidate, request);
        if (isCompletedOrderedReview(candidate)) {
            confirmLateCandidate(work, candidate, new WorldSettingCandidateGroupConfirmRequest.Decision(candidate.getId(),
                    request.operation(), request.category(), request.subjectName(), request.scopeName(),
                    request.settingName(), request.value(), request.conflictResolved(), request.reviewNote()));
            worldSettingCandidateRepository.flush();
            return WorldSettingCandidateConfirmResult.confirmed(worldSettingMapper.toCandidateResponse(candidate));
        }
        if (isInvalidatedOrderedReview(candidate) && !isAuthorEditedDecision(candidate, request)) {
            return markRecomparisonRequired(candidate);
        }
        if (isOrdered(candidate)) {
            WorldSetting current = worldSettingRepository.findByIdentityForUpdate(work.getId(), request.category(),
                    WorldSettingNameNormalizer.duplicateKey(request.subjectName())).orElse(null);
            var selection = new WorldSettingCandidateGroupConfirmRequest.Decision(candidate.getId(),
                    request.operation(), request.category(), request.subjectName(), request.scopeName(),
                    request.settingName(), request.value(), request.conflictResolved(), request.reviewNote());
            var projected = analysisConfirmation.project(List.of(candidate), Map.of(candidate.getId(), selection), current);
            if (projected.isEmpty()) {
                return markRecomparisonRequired(candidate);
            }
            WorldSetting target;
            if (current == null) {
                target = worldSettingRepository.saveAndFlush(WorldSetting.create(work, request.category(),
                        request.subjectName(), projected.get()));
            } else {
                current.replaceConfirmedProperties(projected.get());
                target = current;
            }
            automaticImages.refreshWorldSettingImages(List.of(target));
            candidate.confirm(request.operation(), request.category(), request.subjectName(), request.scopeName(),
                    request.settingName(), request.value(), request.reviewNote(), work.getMember(), target);
            worldSettingCandidateRepository.flush();
            return WorldSettingCandidateConfirmResult.confirmed(worldSettingMapper.toCandidateResponse(candidate));
        }

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
            boolean historyOnly = requiresConfirmedHistoryProtection(candidate)
                    && shouldPreserveCurrentProperty(candidate, currentTarget, request.scopeName(),
                            request.settingName(), request.operation(), new LinkedHashMap<>());
            WorldSetting appliedTarget;
            if (currentTarget == null) {
                appliedTarget = worldSettingRepository.saveAndFlush(worldSettingMapper.toEntity(work, request));
            } else {
                if (!historyOnly) {
                    currentTarget.applyProperty(request.scopeName(), request.settingName(), request.value());
                    worldSettingRepository.flush();
                }
                appliedTarget = currentTarget;
            }
            if (!historyOnly) automaticImages.refreshWorldSettingImages(List.of(appliedTarget));
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
            if (historyOnly) candidate.markHistoryOnly();
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

        if (currentTarget != null && !isCurrentPropertyCompatible(candidate, currentTarget, request)) {
            return markRecomparisonRequired(candidate);
        }
        boolean historyOnly = requiresConfirmedHistoryProtection(candidate)
                && shouldPreserveCurrentProperty(candidate, currentTarget, request.scopeName(),
                        request.settingName(), request.operation(), new LinkedHashMap<>());
        WorldSetting appliedTarget;
        if (currentTarget == null) {
            if (request.operation() != WorldSettingOperation.ADD) {
                return markRecomparisonRequired(candidate);
            }
            appliedTarget = worldSettingRepository.saveAndFlush(
                    worldSettingMapper.toEntity(work, request)
            );
        } else {
            if (!historyOnly) {
                currentTarget.applyProperty(request.scopeName(), request.settingName(), request.value());
                worldSettingRepository.flush();
            }
            appliedTarget = currentTarget;
        }

        if (!historyOnly) automaticImages.refreshWorldSettingImages(List.of(appliedTarget));
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
        if (historyOnly) candidate.markHistoryOnly();
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
        if (candidate.getReviewStatus() == WorldSettingReviewStatus.DISMISSED) {
            return worldSettingMapper.toCandidateResponse(candidate);
        }
        validateReviewMutationAllowed(work, List.of(candidateId));
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
        return confirmCandidateGroupCore(work, request, false);
    }

    private WorldSettingCandidateGroupConfirmResult confirmCandidateGroupCore(
            Work work, WorldSettingCandidateGroupConfirmRequest request, boolean automatic
    ) {
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
        if (!automatic) {
            candidates.forEach(analysisConfirmation::assertReviewSourceCurrent);
            validateReviewMutationAllowed(work, decisionsById.keySet());
        }
        if (candidates.stream().anyMatch(candidate -> candidate.getReviewStatus()
                != WorldSettingReviewStatus.PENDING_REVIEW)) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT);
        }
        if (candidates.stream().anyMatch(candidate -> candidate.getComparisonStatus()
                != WorldSettingComparisonStatus.COMPLETED && !candidate.hasManualReviewDecision())) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_NOT_READY);
        }
        if (automatic && !automaticExclusionsRemainCurrent(candidates)) {
            return WorldSettingCandidateGroupConfirmResult.recomparisonRequired(
                    WorldSettingRecomparisonScope.ROW, WorldSettingRecomparisonReason.PROPERTY_CHANGED,
                    candidates.stream().map(WorldSettingCandidate::getId).toList());
        }

        validateScopeReviewDecisionDrafts(candidates, decisionsById);
        validateResolvedConflicts(candidates, decisionsById);
        if (!automatic && candidates.stream().allMatch(this::isCompletedOrderedReview)) {
            Map<UUID, List<WorldSettingCandidate>> histories = new LinkedHashMap<>();
            for (WorldSettingCandidate candidate : candidates.stream()
                    .sorted(org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingCandidateChronology.comparator()).toList()) {
                var decision = decisionsById.get(candidate.getId());
                if (decision.operation() == WorldSettingOperation.EXCLUDE) candidate.dismiss(decision.reviewNote(), work.getMember());
                else confirmLateCandidate(work, candidate, decision, histories);
            }
            worldSettingCandidateRepository.flush();
            return WorldSettingCandidateGroupConfirmResult.confirmed(worldSettingMapper.toCandidateGroupActionResponse(
                    selectedGroupKey, candidates, singleAppliedTarget(candidates.stream()
                            .map(WorldSettingCandidate::getTargetWorldSetting).filter(Objects::nonNull).toList())));
        }
        if (!automatic && candidates.stream()
                .filter(this::isInvalidatedOrderedReview)
                .anyMatch(candidate -> !isAuthorEditedDecision(candidate, decisionsById.get(candidate.getId())))) {
            return markGroupRecomparisonRequired(
                    work,
                    request.batchId(),
                    selectedGroupKey,
                    WorldSettingRecomparisonReason.PROPERTY_CHANGED
            );
        }
        Set<UUID> rootMoveDecisionIds = rootMoveDecisionIdsToApply(
                candidates,
                decisionsById
        );
        if (!candidates.stream().allMatch(this::isOrdered)) {
            validateDistinctSettingNames(candidates, decisionsById, rootMoveDecisionIds);
        }
        List<WorldSettingCandidate> appliedCandidates = candidates.stream()
                .filter(candidate -> decisionsById.get(candidate.getId()).operation()
                        != WorldSettingOperation.EXCLUDE)
                .toList();
        if (appliedCandidates.isEmpty()) {
            candidates.forEach(candidate -> {
                candidate.dismiss(decisionsById.get(candidate.getId()).reviewNote(), work.getMember());
                if (automatic) {
                    candidate.markAutomaticallyReviewed();
                }
            });
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
        Map<String, List<WorldSetting.Property>> orderedPropertiesByTarget = new LinkedHashMap<>();
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
            if (targetCandidates.stream().allMatch(this::isOrdered)) {
                var projected = automatic
                        ? analysisConfirmation.projectAutomatically(targetCandidates, decisionsById, currentTarget)
                        : analysisConfirmation.projectWithReason(targetCandidates, decisionsById, currentTarget);
                if (projected.properties() == null) {
                    if (automatic) {
                        targetCandidates.forEach(candidate -> candidate.recordAutomaticReviewHold(projected.holdReason()));
                        return WorldSettingCandidateGroupConfirmResult.recomparisonRequired(
                                WorldSettingRecomparisonScope.ROW, WorldSettingRecomparisonReason.PROPERTY_CHANGED,
                                targetCandidates.stream().map(WorldSettingCandidate::getId).toList());
                    }
                    return markGroupRecomparisonRequired(work, request.batchId(), selectedGroupKey,
                            WorldSettingRecomparisonReason.PROPERTY_CHANGED);
                }
                orderedPropertiesByTarget.put(entry.getKey(), projected.properties());
                continue;
            }

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

        Set<UUID> historyOnlyCandidateIds = confirmedHistoryOnlyCandidateIds(
                candidatesByFinalTarget, currentTargetsByKey, decisionsById, rootMoveDecisionIds);
        Map<UUID, WorldSetting> appliedTargetsByCandidateId = new LinkedHashMap<>();
        Set<WorldSetting> changedTargets = new HashSet<>();
        for (Map.Entry<String, List<WorldSettingCandidate>> entry : candidatesByFinalTarget.entrySet()) {
            List<WorldSettingCandidate> targetCandidates = entry.getValue();
            WorldSettingCandidateGroupConfirmRequest.Decision representativeDecision =
                    decisionsById.get(targetCandidates.getFirst().getId());
            Map<String, WorldSetting.Property> propertiesByPath = new LinkedHashMap<>();
            Map<String, WorldSetting.RootPropertyMove> rootMovesByPath = new LinkedHashMap<>();
            for (WorldSettingCandidate candidate : targetCandidates) {
                if (historyOnlyCandidateIds.contains(candidate.getId())) continue;
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
            List<WorldSetting.Property> properties = orderedPropertiesByTarget.getOrDefault(
                    entry.getKey(), List.copyOf(propertiesByPath.values()));
            WorldSetting currentTarget = currentTargetsByKey.get(entry.getKey());
            WorldSetting appliedTarget;
            if (currentTarget == null) {
                appliedTarget = worldSettingRepository.saveAndFlush(WorldSetting.create(
                        work,
                        representativeDecision.category(),
                        representativeDecision.subjectName(),
                        properties
                ));
                changedTargets.add(appliedTarget);
            } else {
                if (orderedPropertiesByTarget.containsKey(entry.getKey())) {
                    currentTarget.replaceConfirmedProperties(properties);
                    changedTargets.add(currentTarget);
                } else if (!properties.isEmpty() || !rootMovesByPath.isEmpty()) {
                    currentTarget.applyRootPropertyMovesAndProperties(List.copyOf(rootMovesByPath.values()), properties);
                    changedTargets.add(currentTarget);
                }
                if (changedTargets.contains(currentTarget)) worldSettingRepository.flush();
                appliedTarget = currentTarget;
            }
            Set<UUID> markedRootMoveDecisionIds = new HashSet<>();
            for (WorldSettingCandidate candidate : targetCandidates) {
                if (!historyOnlyCandidateIds.contains(candidate.getId())
                        && shouldApplyRootPropertyMoves(candidate, rootMoveDecisionIds)
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
                if (historyOnlyCandidateIds.contains(candidate.getId())) candidate.markHistoryOnly();
            }
            if (automatic) {
                candidate.markAutomaticallyReviewed();
            }
        }
        worldSettingCandidateRepository.flush();
        if (!changedTargets.isEmpty()) automaticImages.refreshWorldSettingImages(changedTargets);
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
        if (candidates.stream().allMatch(candidate ->
                candidate.getReviewStatus() == WorldSettingReviewStatus.DISMISSED)) {
            return worldSettingMapper.toCandidateGroupActionResponse(selectedGroupKey, candidates, null);
        }
        validateReviewMutationAllowed(work, candidateIds);
        disableRootPropertyMoves(candidates);
        for (WorldSettingCandidate candidate : candidates) {
            candidate.dismiss(request.reviewNote(), work.getMember());
        }
        worldSettingCandidateRepository.flush();
        return worldSettingMapper.toCandidateGroupActionResponse(selectedGroupKey, candidates, null);
    }

    private boolean sameLateReviewName(String left, String right) {
        return left == null || right == null ? left == right
                : WorldSettingNameNormalizer.duplicateKey(left).equals(WorldSettingNameNormalizer.duplicateKey(right));
    }

    private boolean isCompletedOrderedReview(WorldSettingCandidate candidate) {
        var job = candidate.getAnalysisJob();
        return job != null && job.isOrderedProvisional() && job.getStatus() == AnalysisJobStatus.SUCCEEDED
                && job.getJournalStatus() == AnalysisJournalStatus.SEALED;
    }

    private boolean isInvalidatedOrderedReview(WorldSettingCandidate candidate) {
        var job = candidate.getAnalysisJob();
        return job != null && job.isOrderedProvisional() && job.getStatus() == AnalysisJobStatus.SUCCEEDED
                && job.getJournalStatus() != AnalysisJournalStatus.SEALED;
    }

    private boolean requiresConfirmedHistoryProtection(WorldSettingCandidate candidate) {
        AnalysisJob job = candidate.getAnalysisJob();
        return job != null && job.getAnalysisMode() == AnalysisMode.CONFIRMED_ONLY
                && job.getReviewMode() == AnalysisReviewMode.MANUAL;
    }

    /** 현재 경로를 만든 확정 근거가 더 앞선 회차임을 확인할 수 있을 때만 갱신한다. */
    private boolean shouldPreserveCurrentProperty(
            WorldSettingCandidate candidate, WorldSetting current, String scopeName, String settingName,
            WorldSettingOperation operation, Map<UUID, List<WorldSettingCandidate>> histories
    ) {
        if (current == null) return false;
        String value = current.getPropertyValue(scopeName, settingName);
        List<WorldSettingCandidate> prior = histories.computeIfAbsent(current.getId(), id ->
                new ArrayList<>(worldSettingCandidateRepository
                        .findAllByTargetWorldSettingIdAndReviewStatusOrderByReviewedAtDescCreatedAtDescIdDesc(
                                id, WorldSettingReviewStatus.CONFIRMED))).stream()
                .filter(previous -> !previous.isHistoryOnly()
                        && sameLateReviewName(previous.getFinalScopeName(), scopeName)
                        && sameLateReviewName(previous.getFinalSettingName(), settingName)).toList();
        int episode = candidate.getSourceEpisode() == null ? -1 : candidate.getSourceEpisode().getEpisodeNo();
        return current.isManuallyEdited(scopeName, settingName)
                || current.hasPathConflict(scopeName, settingName)
                || value == null && !prior.isEmpty()
                || value == null && (operation == WorldSettingOperation.UPDATE || operation == WorldSettingOperation.MERGE)
                || value != null && (episode < 1 || prior.isEmpty()
                    || prior.stream().noneMatch(previous -> Objects.equals(previous.getFinalValue(), value))
                    || prior.stream().anyMatch(previous -> previous.getSourceEpisode() == null
                        || previous.getSourceEpisode().getEpisodeNo() >= episode));
    }

    private Set<UUID> confirmedHistoryOnlyCandidateIds(
            Map<String, List<WorldSettingCandidate>> candidatesByTarget,
            Map<String, WorldSetting> currentTargets,
            Map<UUID, WorldSettingCandidateGroupConfirmRequest.Decision> decisions,
            Set<UUID> rootMoveDecisionIds
    ) {
        Map<UUID, List<WorldSettingCandidate>> histories = new LinkedHashMap<>();
        Set<UUID> protectedIds = new HashSet<>();
        Set<UUID> protectedDecisionIds = new HashSet<>();
        for (var entry : candidatesByTarget.entrySet()) {
            WorldSetting current = currentTargets.get(entry.getKey());
            for (WorldSettingCandidate candidate : entry.getValue()) {
                if (!requiresConfirmedHistoryProtection(candidate)) continue;
                var decision = decisions.get(candidate.getId());
                boolean historyOnly = shouldPreserveCurrentProperty(candidate, current, decision.scopeName(),
                        decision.settingName(), decision.operation(), histories);
                if (shouldApplyRootPropertyMoves(candidate, rootMoveDecisionIds)) {
                    for (var move : candidate.getComparisonDecision().getExistingRootPropertyMoveSnapshots()) {
                        // 범위 이동도 기존 경로의 삭제이므로 원본과 이동 목적지 모두 보호한다.
                        historyOnly |= shouldPreserveCurrentProperty(candidate, current, null, move.settingName(),
                                WorldSettingOperation.UPDATE, histories)
                                || shouldPreserveCurrentProperty(candidate, current, decision.scopeName(),
                                        move.settingName(), WorldSettingOperation.ADD, histories);
                    }
                }
                if (historyOnly) {
                    protectedIds.add(candidate.getId());
                    if (candidate.getComparisonDecision() != null) {
                        protectedDecisionIds.add(candidate.getComparisonDecision().getId());
                    }
                }
            }
        }
        // 하나로 병합된 결정을 일부 source만 현재 반영하지 않도록 함께 이력으로 보존한다.
        candidatesByTarget.values().stream().flatMap(List::stream)
                .filter(candidate -> candidate.getComparisonDecision() != null
                        && protectedDecisionIds.contains(candidate.getComparisonDecision().getId()))
                .forEach(candidate -> protectedIds.add(candidate.getId()));
        return protectedIds;
    }

    private void confirmLateCandidate(Work work, WorldSettingCandidate candidate,
            WorldSettingCandidateGroupConfirmRequest.Decision decision) {
        confirmLateCandidate(work, candidate, decision, new LinkedHashMap<>());
    }

    private void confirmLateCandidate(Work work, WorldSettingCandidate candidate,
            WorldSettingCandidateGroupConfirmRequest.Decision decision, Map<UUID, List<WorldSettingCandidate>> histories) {
        if (!candidate.getAnalysisJob().hasCurrentSourceVersion()) throw new AppException(AnalysisJobErrorCode.ANALYSIS_RUN_STATE_CONFLICT);
        var current = worldSettingRepository.findByIdentityForUpdate(work.getId(), decision.category(),
                WorldSettingNameNormalizer.duplicateKey(decision.subjectName())).orElse(null);
        boolean historyOnly = false;
        if (current == null && lateDecisionKeepsOriginalTarget(candidate, decision)) {
            current = worldSettingRepository.findByIdAndWorkIdForUpdate(
                    candidate.getTargetWorldSetting().getId(),
                    work.getId()
            ).orElse(null);
            historyOnly = current != null;
        }
        historyOnly |= shouldPreserveCurrentProperty(candidate, current, decision.scopeName(),
                decision.settingName(), decision.operation(), histories);
        if (current == null) {
            if (decision.operation() != WorldSettingOperation.ADD) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_UPDATE_PATH_NOT_FOUND);
            }
            current = worldSettingRepository.saveAndFlush(WorldSetting.create(work, decision.category(),
                    decision.subjectName(), decision.scopeName(), decision.settingName(), decision.value()));
        } else if (!historyOnly) {
            current.applyProperty(decision.scopeName(), decision.settingName(), decision.value());
        }
        candidate.confirm(decision.operation(), decision.category(), decision.subjectName(), decision.scopeName(),
                decision.settingName(), decision.value(), decision.reviewNote(), work.getMember(), current);
        if (historyOnly) candidate.markHistoryOnly();
        else {
            histories.computeIfAbsent(current.getId(), id -> new ArrayList<>()).add(candidate);
            automaticImages.refreshWorldSettingImages(List.of(current));
        }
    }

    private boolean lateDecisionKeepsOriginalTarget(
            WorldSettingCandidate candidate,
            WorldSettingCandidateGroupConfirmRequest.Decision decision
    ) {
        WorldSetting original = candidate.getTargetWorldSetting();
        if (original == null) return false;
        boolean categoryMatches = decision.category() == original.getCategory()
                || decision.category() == candidate.getCategory();
        return categoryMatches
                && (sameLateReviewName(decision.subjectName(), original.getSubjectName())
                || sameLateReviewName(decision.subjectName(), candidate.getCanonicalSubjectName()));
    }

    private void validateReviewMutationAllowed(Work work, java.util.Collection<UUID> candidateIds) {
        validateAutomaticApplicationNotPending(work, candidateIds);
        analysisRunStateService.assertSettingMutationAllowed(work.getId());
        // 완료된 분석의 입력·결과는 보존한다. 후보 변경만으로 후속 분석을 취소하지 않는다.
    }

    private void validateAutomaticApplicationNotPending(Work work, java.util.Collection<UUID> candidateIds) {
        // 자동 완료도 Work 잠금을 먼저 잡는다. 무효화하거나 후보를 잠그기 전에 원본 분석 상태를 확인한다.
        if (!candidateIds.isEmpty() && worldSettingCandidateRepository
                .findPendingReviewSourceJobs(work.getId(), candidateIds).stream()
                .anyMatch(AnalysisJob::isAutomaticApplicationPending)) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_AUTOMATIC_APPLICATION_PENDING);
        }
    }

    private void enqueueRecomparisonJobIfAbsent(Long memberId, WorldSettingCandidate candidate) {
        if (isOrdered(candidate)) {
            return;
        }
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

    private boolean isOrdered(WorldSettingCandidate candidate) {
        return candidate.getAnalysisJob() != null && candidate.getAnalysisJob().isOrderedProvisional();
    }

    private void validateComparisonReadyAndOperation(
            WorldSettingCandidate candidate,
            WorldSettingCandidateConfirmRequest request
    ) {
        if (candidate.getComparisonStatus() != WorldSettingComparisonStatus.COMPLETED && !candidate.hasManualReviewDecision()) {
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
