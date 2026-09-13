package org.monitoring.catchholebackend.domain.character.service;

import org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
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
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobEpisodeRange;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateCharacterMatchRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateConfirmRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateGroupConfirmRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateGroupConfirmDecision;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateGroupCharacterMatchRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateUpdateRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateListResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateGroupActionResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateGroupResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateReviewStatusResponse;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.mapper.SettingCandidateMapper;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateSchemaMatch;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateSchemaResolver;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateValueValidation;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateChronology;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSettingValueValidator;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateGroupNameNormalizer;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateBatchCounts;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactConfirmApplicationMode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettingCandidateServiceImpl implements SettingCandidateService,
        org.monitoring.catchholebackend.domain.analysis.service.AnalysisAutomaticApplicationContributor {

    private final WorkRepository workRepository;
    private final AnalysisRunStateService analysisRunStateService;
    private final UploadBatchRepository uploadBatchRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final SettingCandidateRepository settingCandidateRepository;
    private final WorkCharacterRepository workCharacterRepository;
    private final CharacterSettingSchemaRepository characterSettingSchemaRepository;
    private final SettingCandidateMapper settingCandidateMapper;
    private final SettingCandidatePromotionService settingCandidatePromotionService;
    private final CharacterFactComparisonWorkerService characterFactComparisonWorkerService;
    private final SettingCandidateSchemaResolver settingCandidateSchemaResolver;
    private final CharacterSettingValueValidator characterSettingValueValidator;
    private final AiTokenService aiTokenService;
    private final CharacterAnalysisConfirmation analysisConfirmation;
    private final CharacterFactComparisonJobCoordinator characterComparisonJobCoordinator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public SettingCandidateListResponse getSettingCandidates(
            Long memberId,
            UUID workId,
            UUID batchId,
            SettingCandidateReviewStatus reviewStatus,
            Set<SettingCandidateMatchStatus> matchStatuses,
            int page,
            int size,
            boolean includeLegacyCandidates
    ) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        uploadBatchRepository.findByIdAndWorkId(batchId, work.getId())
                .orElseThrow(() -> new AppException(CharacterErrorCode.SETTING_CANDIDATE_BATCH_NOT_FOUND));

        List<SettingCandidate> candidates = settingCandidateRepository.findReviewCandidates(
                work.getId(),
                batchId,
                reviewStatus,
                matchStatuses == null || matchStatuses.isEmpty()
                        ? EnumSet.allOf(SettingCandidateMatchStatus.class)
                        : EnumSet.copyOf(matchStatuses)
        );
        Page<SettingCandidate> candidatePage = includeLegacyCandidates
                ? settingCandidateRepository.findReviewPage(
                        work.getId(),
                        batchId,
                        reviewStatus,
                        matchStatuses == null || matchStatuses.isEmpty()
                                ? EnumSet.allOf(SettingCandidateMatchStatus.class)
                                : EnumSet.copyOf(matchStatuses),
                        PageRequest.of(page, size)
                )
                : null;
        SettingCandidateBatchCounts counts = settingCandidateRepository.countReviewSummary(
                work.getId(),
                batchId,
                SettingCandidateReviewStatus.PENDING_REVIEW,
                SettingCandidateMatchStatus.AMBIGUOUS,
                EnumSet.of(CharacterFactComparisonStatus.FAILED, CharacterFactComparisonStatus.RECOMPARISON_REQUIRED)
        );
        AnalysisJobEpisodeRange episodeRange =
                analysisJobRepository.findEpisodeRangeByWorkIdAndBatchId(work.getId(), batchId);
        List<CharacterSettingSchema> schemas =
                characterSettingSchemaRepository.findAllActiveForWork(work.getId());

        Map<String, List<SettingCandidate>> candidatesByGroup = new LinkedHashMap<>();
        candidates.forEach(candidate -> candidatesByGroup
                .computeIfAbsent(groupKey(candidate), ignored -> new ArrayList<>())
                .add(candidate));
        List<Map.Entry<String, List<SettingCandidate>>> orderedGroups = candidatesByGroup.entrySet().stream()
                // 이름을 파악하지 못한 후보는 먼저 볼 수 있는 실제 캐릭터 그룹을 가리지 않도록 마지막에 둔다.
                // Stream.sorted는 stable sort이므로 나머지 그룹의 기존 회차·생성순은 그대로 유지된다.
                .sorted(Comparator.comparing(entry -> isUnknownCharacterName(
                        entry.getValue().getFirst().getEntityName()
                )))
                .toList();
        int fromIndex = (int) Math.min((long) page * size, orderedGroups.size());
        int toIndex = Math.min(fromIndex + size, orderedGroups.size());
        int totalPages = orderedGroups.isEmpty() ? 0 : (orderedGroups.size() + size - 1) / size;
        // 그룹 key를 먼저 페이지로 자른 뒤 선택된 그룹만 DTO로 변환한다.
        // 현재 snapshot 미리보기에서 발생하는 provenance 조회도 요청한 페이지 수에 비례하게 유지된다.
        List<SettingCandidateGroupResponse> pagedGroups = orderedGroups.subList(fromIndex, toIndex).stream()
                .map(entry -> toGroupResponse(entry.getKey(), entry.getValue(), schemas))
                .toList();
        PageResponse<SettingCandidateGroupResponse> groupPage = new PageResponse<>(
                pagedGroups,
                page,
                size,
                orderedGroups.size(),
                totalPages,
                page + 1 < totalPages
        );

        return new SettingCandidateListResponse(
                batchId,
                episodeRange.getEpisodeStartNo(),
                episodeRange.getEpisodeEndNo(),
                episodeRange.getEpisodeCount(),
                counts.getTotalCandidateCount(),
                counts.getReviewedCandidateCount(),
                counts.getPendingCandidateCount(),
                counts.getMatchRequiredCandidateCount(),
                counts.getAttentionRequiredCandidateCount(),
                counts.getConfirmedCandidateCount(),
                counts.getDismissedCandidateCount(),
                counts.getDirectReviewCandidateCount(),
                counts.getProcessingCandidateCount(),
                groupPage,
                candidatePage == null
                        ? null
                        : PageResponse.from(
                                candidatePage,
                                candidatePage.getContent().stream()
                                        .map(candidate -> toReviewListResponse(candidate, schemas))
                                        .toList()
                        )
        );
    }

    @Override
    public SettingCandidateResponse getSettingCandidate(
            Long memberId,
            UUID workId,
            UUID batchId,
            UUID candidateId
    ) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        SettingCandidate candidate = settingCandidateRepository
                .findByIdAndWorkIdAndAnalysisJobBatchId(candidateId, work.getId(), batchId)
                .orElseThrow(() -> new AppException(CharacterErrorCode.SETTING_CANDIDATE_NOT_FOUND));
        List<CharacterSettingSchema> schemas =
                characterSettingSchemaRepository.findAllActiveForWork(work.getId());
        return toResponse(candidate, schemas);
    }

    @Override
    @Transactional
    public SettingCandidateResponse updateSettingCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId,
            SettingCandidateUpdateRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        invalidateSelectedRuns(work, List.of(candidateId));
        SettingCandidate candidate = getCandidateInWork(candidateId, work);
        candidate.validateReviewContentEditable();
        validateComparisonNotProcessing(candidate);
        List<CharacterSettingSchema> schemas =
                characterSettingSchemaRepository.findAllActiveForWork(candidate.getWork().getId());

        CandidateReviewContent reviewContent = resolveReviewContent(
                candidate,
                normalizeRequiredText(request.attributeName()),
                normalizeOptionalText(request.attributeValue()),
                schemas
        );
        if (candidate.isManualReviewAvailable() && !reviewContent.updateRequired()) {
            // 실패 후보도 원문 값 그대로를 명시적으로 확인해 직접 반영할 수 있다.
            candidate.recordUserModification();
        }
        if (reviewContent.updateRequired()) {
            candidate.updateReviewContent(
                    reviewContent.attributeName(),
                    reviewContent.attributeValue(),
                    reviewContent.valueJson()
            );
        }
        SettingCandidateSchemaMatch schemaMatch = settingCandidateSchemaResolver.resolve(
                candidate.getAttributeName(),
                candidate.getValueType(),
                schemas
        );
        characterSettingValueValidator.validateCandidate(
                candidate,
                schemaMatch.matchedSchema().getFactType(),
                schemaMatch.matchedSchema().getValueType()
        );
        if (reviewContent.updateRequired()) {
            enqueueComparisonJobIfNeeded(memberId, candidate);
        }
        return toResponse(candidate, schemas);
    }

    @Override
    @Transactional
    public SettingCandidateGroupActionResponse updateSettingCandidateGroupCharacterMatch(
            Long memberId,
            UUID workId,
            SettingCandidateGroupCharacterMatchRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        uploadBatchRepository.findByIdAndWorkId(request.batchId(), work.getId())
                .orElseThrow(() -> new AppException(CharacterErrorCode.SETTING_CANDIDATE_BATCH_NOT_FOUND));
        Set<UUID> requestedIds = Set.copyOf(request.candidateIds());
        if (requestedIds.size() != request.candidateIds().size()) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT);
        }
        invalidateSelectedRuns(work, requestedIds);
        List<SettingCandidate> candidates = settingCandidateRepository.findAllByIdsAndBatchForUpdate(
                work.getId(), request.batchId(), requestedIds
        );
        validateCompletePendingGroup(work, request.batchId(), candidates, requestedIds);
        candidates.forEach(candidate -> {
            validateComparisonNotProcessing(candidate);
        });
        List<CharacterFactComparisonJobCoordinator.ScopeRef> previousScopes =
                characterComparisonJobCoordinator.scopeRefs(candidates);
        applyCharacterMatch(
                candidates,
                work,
                request.resolutionType(),
                request.matchedCharacterId(),
                request.entityName()
        );
        candidates.forEach(candidate -> enqueueComparisonJobIfNeeded(memberId, candidate));
        enqueuePreviousScopes(memberId, previousScopes, candidates);
        settingCandidateRepository.flush();
        List<CharacterSettingSchema> schemas = characterSettingSchemaRepository.findAllActiveForWork(work.getId());
        return toGroupActionResponse(groupKey(candidates.getFirst()), candidates, schemas);
    }

    @Override
    @Transactional
    public SettingCandidateResponse updateSettingCandidateCharacterMatch(
            Long memberId,
            UUID workId,
            UUID candidateId,
            SettingCandidateCharacterMatchRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        invalidateSelectedRuns(work, List.of(candidateId));
        SettingCandidate candidate = getCandidateInWork(candidateId, work);
        candidate.validateEditable();
        validateComparisonNotProcessing(candidate);
        List<CharacterFactComparisonJobCoordinator.ScopeRef> previousScopes =
                characterComparisonJobCoordinator.scopeRefs(List.of(candidate));

        // 사용자가 기존 캐릭터를 지정하면 즉시 MATCHED로, 신규로 판단하면 confirm 전까지 UNRESOLVED로 둔다.
        applyCharacterMatch(
                List.of(candidate),
                work,
                request.resolutionType(),
                request.matchedCharacterId(),
                request.entityName()
        );
        enqueueComparisonJobIfNeeded(memberId, candidate);
        enqueuePreviousScopes(memberId, previousScopes, List.of(candidate));

        List<CharacterSettingSchema> schemas =
                characterSettingSchemaRepository.findAllActiveForWork(candidate.getWork().getId());
        return toResponse(candidate, schemas);
    }

    @Override
    @Transactional
    public SettingCandidateConfirmResult confirmSettingCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId,
            SettingCandidateConfirmRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        invalidateSelectedRuns(work, List.of(candidateId));
        SettingCandidate candidate = getCandidateInWork(candidateId, work);
        if (candidate.getReviewStatus() == SettingCandidateReviewStatus.CONFIRMED) {
            return SettingCandidateConfirmResult.confirmed(
                    settingCandidateMapper.toReviewStatusResponse(candidate)
            );
        }
        if (candidate.getReviewStatus() != SettingCandidateReviewStatus.PENDING_REVIEW) {
            candidate.confirm();
        }

        if (prepareUnresolvedExistingCharacterForComparison(memberId, candidate, work)) {
            return SettingCandidateConfirmResult.recomparisonRequired(
                    settingCandidateMapper.toReviewStatusResponse(candidate)
            );
        }

        // Java-first 순차 배포 중 구버전 AI가 남긴 미준비 후보는 확정을 우회하지 않고
        // 신규 hidden 그룹 비교 Job으로 복구한다.
        if (!candidate.isCharacterDiscovery()
                && !isOrdered(candidate)
                && candidate.getComparisonStatus() != CharacterFactComparisonStatus.COMPLETED) {
            if (candidate.getComparisonStatus() != CharacterFactComparisonStatus.PENDING
                    && candidate.getComparisonStatus() != CharacterFactComparisonStatus.PROCESSING) {
                candidate.requestComparison();
            }
            enqueueComparisonJobIfNeeded(memberId, candidate);
            settingCandidateRepository.flush();
            return SettingCandidateConfirmResult.recomparisonRequired(
                    settingCandidateMapper.toReviewStatusResponse(candidate)
            );
        }

        CharacterFactConfirmApplicationMode applicationMode = request == null
                || request.applicationMode() == null
                ? CharacterFactConfirmApplicationMode.APPLY_PROPOSAL
                : request.applicationMode();
        boolean applyEditedValue = request != null && Boolean.TRUE.equals(request.applyEditedValue());
        if (applyEditedValue) {
            prepareUserEditedValue(candidate);
        }
        if (applicationMode == CharacterFactConfirmApplicationMode.APPLY_PROPOSAL
                && candidate.hasComparisonDependencies()
                && (!isOrdered(candidate) || comparisonDependencyIds(candidate).stream()
                .anyMatch(id -> !analysisConfirmation.isDependencyConfirmed(candidate, id)))) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_GROUP_DECISION_DEPENDENCY_CONFLICT);
        }
        // 직접 확인한 값은 잠근 현재 설정으로 새 제안을 만든다. 요청의 이전 AI 비교 버전은 재사용하지 않는다.
        validateConfirmPolicy(candidate, applicationMode,
                applyEditedValue || request == null ? null : request.baseSnapshotVersion());
        if (applicationMode == CharacterFactConfirmApplicationMode.APPLY_PROPOSAL
                && !candidate.isCharacterDiscovery()
                && !applyEditedValue && !hasCurrentContext(candidate, List.of())) {
            requestRecomparisonAfterStale(candidate);
            enqueueComparisonJobIfNeeded(memberId, candidate);
            settingCandidateRepository.flush();
            return SettingCandidateConfirmResult.recomparisonRequired(
                    settingCandidateMapper.toReviewStatusResponse(candidate)
            );
        }

        // 최초 PENDING_REVIEW -> CONFIRMED 전이만 true다. 동일 confirm 재시도는 false로 Fact 중복 생성을 막는다.
        boolean newlyConfirmed = candidate.confirm();
        if (newlyConfirmed) {
            settingCandidatePromotionService.promote(candidate, applicationMode);
        }
        return SettingCandidateConfirmResult.confirmed(settingCandidateMapper.toReviewStatusResponse(candidate));
    }

    @Override
    @Transactional
    public SettingCandidateGroupConfirmResult confirmSettingCandidateGroup(
            Long memberId,
            UUID workId,
            SettingCandidateGroupConfirmRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        uploadBatchRepository.findByIdAndWorkId(request.batchId(), work.getId())
                .orElseThrow(() -> new AppException(CharacterErrorCode.SETTING_CANDIDATE_BATCH_NOT_FOUND));
        Map<UUID, SettingCandidateGroupConfirmDecision> decisions = new LinkedHashMap<>();
        request.candidates().forEach(decision -> {
            if (decisions.putIfAbsent(decision.candidateId(), decision) != null) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT);
            }
        });
        invalidateSelectedRuns(work, decisions.keySet());
        List<SettingCandidate> candidates = SettingCandidateChronology.sorted(
                settingCandidateRepository.findAllByIdsAndBatchForUpdate(
                        work.getId(), request.batchId(), decisions.keySet()
                )
        );
        String decisionHash = groupDecisionHash(request);
        if (candidates.size() == decisions.size()
                && candidates.stream().noneMatch(SettingCandidate::isPendingReview)) {
            if (candidates.stream().allMatch(candidate ->
                    Objects.equals(candidate.getConfirmedGroupDecisionHash(), decisionHash))) {
                List<CharacterSettingSchema> schemas =
                        characterSettingSchemaRepository.findAllActiveForWork(work.getId());
                return SettingCandidateGroupConfirmResult.confirmed(toGroupActionResponse(
                        groupKey(candidates.getFirst()),
                        candidates,
                        schemas
                ));
            }
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT);
        }
        validateCompletePendingGroup(work, request.batchId(), candidates, decisions.keySet());
        if (candidates.stream().anyMatch(candidate -> candidate.getMatchStatus()
                == SettingCandidateMatchStatus.AMBIGUOUS)) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCH_STATUS_CONFLICT);
        }

        // 같은 이름의 기존 캐릭터가 확인되면 모든 UNRESOLVED 행을 먼저 연결한다.
        // 연결 전 문맥으로는 확정하지 않고 그룹 전체를 숨김 비교 Worker에 다시 맡긴다.
        List<SettingCandidate> unresolved = candidates.stream()
                .filter(candidate -> !isOrdered(candidate)
                        && candidate.getMatchStatus() == SettingCandidateMatchStatus.UNRESOLVED)
                .toList();
        if (!unresolved.isEmpty()) {
            String entityName = SettingCandidateGroupNameNormalizer.toDisplayName(
                    unresolved.getFirst().getEntityName()
            );
            WorkCharacter existing = findActiveCharacterByGroupName(work.getId(), entityName).orElse(null);
            if (existing != null) {
                WorkCharacter locked = workCharacterRepository.findByIdAndWorkIdForUpdate(
                                existing.getId(), work.getId()
                        )
                        .orElseThrow(() -> new AppException(
                                CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID
                        ));
                if (locked.getStatus() != CharacterStatus.ACTIVE) {
                    throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED);
                }
                unresolved.forEach(candidate -> {
                    candidate.matchExistingCharacter(locked);
                    enqueueComparisonJobIfNeeded(memberId, candidate);
                });
                settingCandidateRepository.flush();
                return SettingCandidateGroupConfirmResult.recomparisonRequired(
                        unresolved.stream().map(SettingCandidate::getId).toList()
                );
            }
            if (existsCharacterByGroupName(work.getId(), entityName)) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED);
            }
            if (unresolved.size() != candidates.size()) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCH_STATUS_CONFLICT);
            }
            List<UUID> pendingComparisons = new ArrayList<>();
            for (SettingCandidate candidate : candidates) {
                if (candidate.isCharacterDiscovery() || isOrdered(candidate)
                        || candidate.getComparisonStatus() == CharacterFactComparisonStatus.COMPLETED) {
                    continue;
                }
                if (candidate.getComparisonStatus() != CharacterFactComparisonStatus.PENDING
                        && candidate.getComparisonStatus() != CharacterFactComparisonStatus.PROCESSING) {
                    candidate.requestComparison();
                }
                enqueueComparisonJobIfNeeded(memberId, candidate);
                pendingComparisons.add(candidate.getId());
            }
            if (!pendingComparisons.isEmpty()) {
                settingCandidateRepository.flush();
                return SettingCandidateGroupConfirmResult.recomparisonRequired(pendingComparisons);
            }

            for (SettingCandidate candidate : candidates) {
                if (candidate.isCharacterDiscovery()) {
                    continue;
                }
                SettingCandidateGroupConfirmDecision decision = decisions.get(candidate.getId());
                if (Boolean.TRUE.equals(decision.applyEditedValue())) {
                    prepareUserEditedValue(candidate);
                }
                if (candidate.getSuggestedOperation() != CharacterFactOperation.EXCLUDE) {
                    validateConfirmPolicy(candidate, decision.applicationMode(),
                            Boolean.TRUE.equals(decision.applyEditedValue()) ? null : decision.baseSnapshotVersion());
                }
                if (!isOrdered(candidate) && !characterFactComparisonWorkerService.hasCurrentContext(candidate)) {
                    candidate.requestComparison();
                    enqueueComparisonJobIfNeeded(memberId, candidate);
                    pendingComparisons.add(candidate.getId());
                }
            }
            if (!pendingComparisons.isEmpty()) {
                settingCandidateRepository.flush();
                return SettingCandidateGroupConfirmResult.recomparisonRequired(pendingComparisons);
            }

            List<CharacterSettingSchema> schemas = characterSettingSchemaRepository.findAllActiveForWork(work.getId());
            validateComparisonRevision(candidates, request.comparisonRevision());
            validateGroupDecisionDependencies(candidates, decisions, schemas);
            candidates.stream()
                    .filter(candidate -> candidate.getSuggestedOperation() == CharacterFactOperation.EXCLUDE)
                    .forEach(SettingCandidate::dismiss);
            List<SettingCandidateGroupPromotion> promotions = candidates.stream()
                    .filter(candidate -> candidate.getSuggestedOperation() != CharacterFactOperation.EXCLUDE)
                    .map(candidate -> new SettingCandidateGroupPromotion(
                            candidate,
                            decisions.get(candidate.getId()).applicationMode()
                    ))
                    .toList();
            List<SettingCandidate> earlierApplied = new ArrayList<>();
            if (candidates.stream().filter(candidate -> candidate.getProvisionalSubjectKey() != null)
                    .map(SettingCandidate::getProvisionalSubjectKey).distinct().count() > 1) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCH_STATUS_CONFLICT);
            }
            for (SettingCandidate candidate : candidates) {
                if (isOrdered(candidate) && decisions.get(candidate.getId()).applicationMode()
                        == CharacterFactConfirmApplicationMode.APPLY_PROPOSAL
                        && !Boolean.TRUE.equals(decisions.get(candidate.getId()).applyEditedValue())) {
                    if (!hasCurrentContext(candidate, earlierApplied)) {
                        throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STALE);
                    }
                    earlierApplied.add(candidate);
                }
            }
            if (promotions.isEmpty()) {
                candidates.forEach(candidate -> candidate.recordGroupConfirmation(decisionHash));
                return SettingCandidateGroupConfirmResult.confirmed(
                        toGroupActionResponse(groupKey(candidates.getFirst()), candidates, schemas)
                );
            }
            settingCandidatePromotionService.promoteNewCharacterGroup(promotions);
            candidates.forEach(candidate -> candidate.recordGroupConfirmation(decisionHash));
            return SettingCandidateGroupConfirmResult.confirmed(
                    toGroupActionResponse(groupKey(candidates.getFirst()), candidates, schemas)
            );
        }

        for (SettingCandidate candidate : candidates) {
            if (Boolean.TRUE.equals(decisions.get(candidate.getId()).applyEditedValue())) {
                prepareUserEditedValue(candidate);
            }
        }

        List<UUID> bootstrapped = new ArrayList<>();
        for (SettingCandidate candidate : candidates) {
            if (!candidate.isCharacterDiscovery()
                    && candidate.getMatchedCharacterId() != null
                    && candidate.getComparisonStatus() == CharacterFactComparisonStatus.NOT_REQUIRED) {
                requestRecomparisonAfterStale(candidate);
                enqueueComparisonJobIfNeeded(memberId, candidate);
                bootstrapped.add(candidate.getId());
            }
        }
        if (!bootstrapped.isEmpty()) {
            settingCandidateRepository.flush();
            return SettingCandidateGroupConfirmResult.recomparisonRequired(bootstrapped);
        }

        // 한 행을 반영한 결과가 다음 행의 기존 context hash를 바꾸기 전에 그룹 전체를 먼저 검증한다.
        List<SettingCandidate> earlierApplied = new ArrayList<>();
        for (SettingCandidate candidate : candidates) {
            SettingCandidateGroupConfirmDecision decision = decisions.get(candidate.getId());
            // EXCLUDE는 현재값이나 이력을 만들지 않는 것도 하나의 AI 제안이다. 그룹 전체 확정에서는
            // 사용자가 그 제안을 승인한 것으로 보고 아래 반영 단계에서 자동 무시 처리한다.
            if (candidate.getSuggestedOperation() == CharacterFactOperation.EXCLUDE) {
                continue;
            }
            validateConfirmPolicy(candidate, decision.applicationMode(),
                            Boolean.TRUE.equals(decision.applyEditedValue()) ? null : decision.baseSnapshotVersion());
            if (decision.applicationMode() == CharacterFactConfirmApplicationMode.APPLY_PROPOSAL
                    && !candidate.isCharacterDiscovery()
                    && !Boolean.TRUE.equals(decision.applyEditedValue())
                    && !hasCurrentContext(candidate, earlierApplied)) {
                requestRecomparisonAfterStale(candidate);
                enqueueComparisonJobIfNeeded(memberId, candidate);
                bootstrapped.add(candidate.getId());
            }
            if (decision.applicationMode() == CharacterFactConfirmApplicationMode.APPLY_PROPOSAL) {
                earlierApplied.add(candidate);
            }
        }
        if (!bootstrapped.isEmpty()) {
            settingCandidateRepository.flush();
            return SettingCandidateGroupConfirmResult.recomparisonRequired(bootstrapped);
        }

        List<CharacterSettingSchema> schemas = characterSettingSchemaRepository.findAllActiveForWork(work.getId());
        validateComparisonRevision(candidates, request.comparisonRevision());
        validateGroupDecisionDependencies(candidates, decisions, schemas);

        List<SettingCandidateGroupPromotion> promotions = new ArrayList<>();
        for (SettingCandidate candidate : candidates) {
            if (candidate.getSuggestedOperation() == CharacterFactOperation.EXCLUDE) {
                candidate.dismiss();
                continue;
            }
            if (candidate.confirm()) {
                promotions.add(new SettingCandidateGroupPromotion(
                        candidate,
                        decisions.get(candidate.getId()).applicationMode()
                ));
            }
        }
        settingCandidatePromotionService.promoteGroup(promotions);
        candidates.forEach(candidate -> candidate.recordGroupConfirmation(decisionHash));
        return SettingCandidateGroupConfirmResult.confirmed(
                toGroupActionResponse(groupKey(candidates.getFirst()), candidates, schemas)
        );
    }

    @Override
    public String applicationDomain() { return "characters"; }

    @Override
    @Transactional
    public void applyAutomatically(AnalysisJob job) {
        if (!job.isAutomaticReview()) return;
        List<SettingCandidate> candidates = SettingCandidateChronology.sorted(
                settingCandidateRepository.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(job.getId()));
        List<SettingCandidate> accepted = new ArrayList<>();
        Set<UUID> acceptedIds = new HashSet<>();
        Set<String> blockedSubjects = new HashSet<>();
        Map<String, Set<String>> newNames = new HashMap<>();
        for (SettingCandidate discovery : candidates) {
            if (!discovery.isCharacterDiscovery() || discovery.getProvisionalSubjectKey() == null) continue;
            String subject = discovery.getProvisionalSubjectKey();
            if (!discovery.isPendingReview() || discovery.isUserModified()
                    || isUnknownCharacterName(discovery.getEntityName())
                    || discovery.getMatchStatus() == SettingCandidateMatchStatus.AMBIGUOUS
                    || analysisConfirmation.resolvedCharacterId(discovery) == null
                    && existsCharacterByGroupName(job.getWork().getId(), discovery.getEntityName())) {
                blockedSubjects.add(subject);
            }
            newNames.computeIfAbsent(groupKey(discovery.getEntityName()), ignored -> new HashSet<>()).add(subject);
        }
        newNames.values().stream().filter(subjects -> subjects.size() > 1).forEach(blockedSubjects::addAll);
        List<SettingCandidate> applicationOrder = new ArrayList<>();
        candidates.stream().filter(SettingCandidate::isCharacterDiscovery).forEach(applicationOrder::add);
        candidates.stream().filter(candidate -> !candidate.isCharacterDiscovery()).forEach(applicationOrder::add);
        for (SettingCandidate candidate : applicationOrder) {
            if (!candidate.isPendingReview() || candidate.isUserModified()
                    || isUnknownCharacterName(candidate.getEntityName())) continue;
            String subject = candidate.getProvisionalSubjectKey();
            if (candidate.getMatchStatus() == SettingCandidateMatchStatus.AMBIGUOUS
                    || candidate.getMatchedCharacterId() == null && subject == null) continue;
            if (subject != null && blockedSubjects.contains(subject)) {
                candidate.recordAutomaticReviewHold(AutomaticReviewHoldReason.SUBJECT_CONFIRMATION_REQUIRED);
                continue;
            }
            if (!candidate.isCharacterDiscovery()) {
                if (!candidate.isComparisonCompleted() || candidate.getSuggestedOperation() == null
                        || candidate.getSuggestedOperation() == CharacterFactOperation.REVIEW_REQUIRED) continue;
                if (subject != null && analysisConfirmation.resolvedCharacterId(candidate) == null
                        && accepted.stream().noneMatch(discovery -> discovery.isCharacterDiscovery()
                        && subject.equals(discovery.getProvisionalSubjectKey()))) {
                    candidate.recordAutomaticReviewHold(AutomaticReviewHoldReason.SUBJECT_CONFIRMATION_REQUIRED);
                    continue;
                }
                boolean priorHumanRejection = candidate.getRawComparisonJson() != null
                        && "USER_REJECTION".equals(candidate.getRawComparisonJson().path("origin").asText());
                if (candidate.getSuggestedOperation() == CharacterFactOperation.EXCLUDE && priorHumanRejection) {
                    candidate.dismiss();
                    candidate.markAutomaticallyReviewed();
                    continue;
                }
                boolean dependencyMissing = comparisonDependencyIds(candidate).stream()
                        .anyMatch(id -> !acceptedIds.contains(id) && !analysisConfirmation.isDependencyConfirmed(candidate, id));
                if (dependencyMissing || !hasCurrentContext(candidate, accepted)) {
                    candidate.recordAutomaticReviewHold(dependencyMissing ? AutomaticReviewHoldReason.DEPENDENCY_CONFIRMATION_REQUIRED : AutomaticReviewHoldReason.CURRENT_SETTING_CHANGED);
                    continue;
                }
                if (candidate.getSuggestedOperation() == CharacterFactOperation.EXCLUDE) {
                    candidate.dismiss();
                    candidate.markAutomaticallyReviewed();
                    continue;
                }
                try {
                    validateConfirmPolicy(candidate, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, null);
                    var schema = settingCandidateSchemaResolver.resolve(candidate.getAttributeName(), candidate.getValueType(),
                            characterSettingSchemaRepository.findAllActiveForWork(job.getWork().getId()));
                    characterSettingValueValidator.validateCandidate(candidate, schema.matchedSchema().getFactType(),
                            schema.matchedSchema().getValueType());
                    characterSettingValueValidator.resolveCandidateValue(candidate, schema.matchedSchema().getFactType(),
                            schema.matchedSchema().getValueType());
                    settingCandidatePromotionService.validateAutomaticPromotion(candidate, accepted);
                } catch (AppException invalidProposal) {
                    candidate.recordAutomaticReviewHold(AutomaticReviewHoldReason.SETTING_VALUE_CONFIRMATION_REQUIRED);
                    continue;
                }
            }
            accepted.add(candidate);
            acceptedIds.add(candidate.getId());
        }
        List<SettingCandidateGroupPromotion> promotions = new ArrayList<>();
        for (SettingCandidate candidate : accepted) {
            if (candidate.confirm()) promotions.add(new SettingCandidateGroupPromotion(
                    candidate, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL));
        }
        settingCandidatePromotionService.promoteGroup(promotions);
        accepted.forEach(SettingCandidate::markAutomaticallyReviewed);
        settingCandidateRepository.flush();
    }

    private void prepareUserEditedValue(SettingCandidate candidate) {
        if (!isOrdered(candidate) || !candidate.isUserModified()) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
        }
        if (candidate.isCharacterDiscovery()) {
            return;
        }
        List<CharacterSettingSchema> schemas = characterSettingSchemaRepository.findAllActiveForWork(candidate.getWork().getId());
        SettingCandidateSchemaMatch schema = settingCandidateSchemaResolver.resolve(
                candidate.getAttributeName(), candidate.getValueType(), schemas);
        CharacterFactType factType = schema.matchedSchema().getFactType();
        characterSettingValueValidator.validateCandidate(candidate, factType, schema.matchedSchema().getValueType());
        JsonNode typedValue = characterSettingValueValidator.resolveCandidateValue(candidate, factType,
                schema.matchedSchema().getValueType());
        UUID actualId = analysisConfirmation.resolvedCharacterId(candidate);
        long version = actualId == null ? 0 : workCharacterRepository.findByIdAndWorkIdForUpdate(
                actualId, candidate.getWork().getId()).orElseThrow(() ->
                new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID)).getSnapshotVersion();
        Map<CharacterSnapshotSlot, org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry> snapshot =
                analysisConfirmation.loadActual(candidate);
        if (snapshot == null && actualId != null) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID);
        }
        String key = schema.factKey();
        CharacterFactOperation operation = snapshot != null && snapshot.containsKey(new CharacterSnapshotSlot(factType, key))
                ? CharacterFactOperation.UPDATE : CharacterFactOperation.ADD;
        candidate.prepareUserEditedValue(factType, key, candidate.getAttributeValue(), typedValue, operation, version);
    }

    private void requestRecomparisonAfterStale(SettingCandidate candidate) {
        if (isOrdered(candidate)) {
            candidate.markRecomparisonRequired("누적 분석 입력이 변경되어 명시적 재분석이 필요합니다.");
        } else {
            candidate.requestComparison();
        }
    }

    private boolean isOrdered(SettingCandidate candidate) {
        return candidate.getAnalysisJob() != null && candidate.getAnalysisJob().isOrderedProvisional();
    }

    private boolean hasCurrentContext(SettingCandidate candidate, List<SettingCandidate> earlierApplied) {
        return isOrdered(candidate) ? analysisConfirmation.hasCurrentContext(candidate, earlierApplied)
                : characterFactComparisonWorkerService.hasCurrentContext(candidate);
    }

    @Override
    @Transactional
    public SettingCandidateResponse retryComparison(Long memberId, UUID workId, UUID candidateId) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        validateAutomaticApplicationNotPending(work, List.of(candidateId));
        SettingCandidate candidate = getCandidateInWork(candidateId, work);
        if (isOrdered(candidate)) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_ORDERED_JOB_RETRY_REQUIRED);
        }
        candidate.validateReviewContentEditable();
        validateComparisonNotProcessing(candidate);
        if (candidate.getComparisonStatus() == CharacterFactComparisonStatus.COMPLETED) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
        }
        List<CharacterSettingSchema> schemas = characterSettingSchemaRepository.findAllActiveForWork(work.getId());
        SettingCandidateSchemaMatch schemaMatch = settingCandidateSchemaResolver.resolve(
                candidate.getAttributeName(),
                candidate.getValueType(),
                schemas
        );
        characterSettingValueValidator.validateCandidate(
                candidate,
                schemaMatch.matchedSchema().getFactType(),
                schemaMatch.matchedSchema().getValueType()
        );
        candidate.requestComparison();
        enqueueComparisonJobIfNeeded(memberId, candidate);
        return toResponse(candidate, schemas);
    }

    @Override
    @Transactional
    public SettingCandidateReviewStatusResponse dismissSettingCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        invalidateSelectedRuns(work, List.of(candidateId));
        SettingCandidate candidate = getCandidateInWork(candidateId, work);
        candidate.recordUserModification();
        List<CharacterFactComparisonJobCoordinator.ScopeRef> previousScopes =
                characterComparisonJobCoordinator.scopeRefs(List.of(candidate));
        candidate.dismiss();
        characterComparisonJobCoordinator.enqueueScopes(memberId, previousScopes);
        return settingCandidateMapper.toReviewStatusResponse(candidate);
    }

    private boolean prepareUnresolvedExistingCharacterForComparison(
            Long memberId,
            SettingCandidate candidate,
            Work work
    ) {
        if (isOrdered(candidate) || candidate.isCharacterDiscovery()
                || candidate.getMatchStatus() != SettingCandidateMatchStatus.UNRESOLVED
                || candidate.getMatchedCharacterId() != null) {
            return false;
        }
        String entityName = SettingCandidateGroupNameNormalizer.toDisplayName(candidate.getEntityName());
        WorkCharacter existing = findActiveCharacterByGroupName(work.getId(), entityName).orElse(null);
        if (existing == null) {
            if (existsCharacterByGroupName(work.getId(), entityName)) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED);
            }
            return false;
        }
        WorkCharacter locked = workCharacterRepository.findByIdAndWorkIdForUpdate(
                        existing.getId(),
                        work.getId()
                )
                .orElseThrow(() -> new AppException(
                        CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID
                ));
        if (locked.getStatus() != CharacterStatus.ACTIVE) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED);
        }
        List<CharacterFactComparisonJobCoordinator.ScopeRef> previousScopes =
                characterComparisonJobCoordinator.scopeRefs(List.of(candidate));
        candidate.matchExistingCharacter(locked);
        enqueueComparisonJobIfNeeded(memberId, candidate);
        enqueuePreviousScopes(memberId, previousScopes, List.of(candidate));
        settingCandidateRepository.flush();
        return true;
    }

    private void validateConfirmPolicy(
            SettingCandidate candidate,
            CharacterFactConfirmApplicationMode applicationMode,
            Long requestedBaseSnapshotVersion
    ) {
        if (candidate.isCharacterDiscovery()) {
            return;
        }
        if (candidate.getComparisonStatus() != CharacterFactComparisonStatus.COMPLETED
                || candidate.getSuggestedOperation() == null) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_NOT_READY);
        }
        if (applicationMode == CharacterFactConfirmApplicationMode.APPLY_PROPOSAL
                && requestedBaseSnapshotVersion != null
                && !Objects.equals(requestedBaseSnapshotVersion, candidate.getComparisonBaseSnapshotVersion())) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STALE);
        }
        CharacterFactOperation operation = candidate.getSuggestedOperation();
        if (operation == CharacterFactOperation.EXCLUDE) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID);
        }
        if (applicationMode == CharacterFactConfirmApplicationMode.APPLY_PROPOSAL
                && operation == CharacterFactOperation.REVIEW_REQUIRED) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID);
        }
    }

    /**
     * 뒤 후보의 비교 결과가 앞선 후보의 제안값이나 제거로 생긴 slot 부재를 문맥으로 사용했는데 사용자가
     * 그 앞 후보를 HISTORY_ONLY로 바꾸면, 뒤 제안만 적용할 때 검증하지 않은 현재값이 만들어진다. 그룹
     * 확정은 원자적으로 중단해 사용자가 두 후보의 반영 방식을 일관되게 다시 선택하도록 한다.
     */
    private void validateGroupDecisionDependencies(
            List<SettingCandidate> candidates,
            Map<UUID, SettingCandidateGroupConfirmDecision> decisions,
            List<CharacterSettingSchema> schemas
    ) {
        Map<UUID, Integer> chronology = new HashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            chronology.put(candidates.get(index).getId(), index);
        }
        for (SettingCandidate candidate : candidates) {
            SettingCandidateGroupConfirmDecision decision = decisions.get(candidate.getId());
            if (decision.applicationMode() != CharacterFactConfirmApplicationMode.APPLY_PROPOSAL) {
                continue;
            }
            for (UUID dependencyId : comparisonDependencyIds(candidate)) {
                if (isOrdered(candidate) && !chronology.containsKey(dependencyId)
                        && analysisConfirmation.isDependencyConfirmed(candidate, dependencyId)) {
                    continue;
                }
                Integer dependencyIndex = chronology.get(dependencyId);
                Integer candidateIndex = chronology.get(candidate.getId());
                SettingCandidateGroupConfirmDecision dependencyDecision = decisions.get(dependencyId);
                if (dependencyIndex == null
                        || candidateIndex == null
                        || dependencyIndex >= candidateIndex
                        || dependencyDecision == null
                        || dependencyDecision.applicationMode()
                        != CharacterFactConfirmApplicationMode.APPLY_PROPOSAL) {
                    throw new AppException(
                            CharacterErrorCode.SETTING_CANDIDATE_GROUP_DECISION_DEPENDENCY_CONFLICT
                    );
                }
            }
        }

        // 배치 의존성 정보가 없는 구버전 단건 결과도 기존 동일-slot 보호를 유지한다.
        Set<CharacterSnapshotSlot> suppressedPriorProposalSlots = new HashSet<>();
        for (SettingCandidate candidate : candidates) {
            CharacterFactOperation operation = candidate.getSuggestedOperation();
            if (candidate.isCharacterDiscovery() || !changesCurrentSnapshot(operation)) {
                continue;
            }
            SettingCandidateSchemaMatch schemaMatch = settingCandidateSchemaResolver.resolve(
                    candidate.getAttributeName(),
                    candidate.getValueType(),
                    schemas
            );
            CharacterSnapshotSlot slot = new CharacterSnapshotSlot(
                    schemaMatch.matchedSchema().getFactType(),
                    candidate.getResolvedCanonicalFactKey() == null
                            || candidate.getResolvedCanonicalFactKey().isBlank()
                            ? schemaMatch.factKey()
                            : candidate.getResolvedCanonicalFactKey().trim()
            );
            CharacterFactConfirmApplicationMode applicationMode =
                    decisions.get(candidate.getId()).applicationMode();
            if (applicationMode == CharacterFactConfirmApplicationMode.APPLY_PROPOSAL
                    && suppressedPriorProposalSlots.contains(slot)) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_GROUP_DECISION_DEPENDENCY_CONFLICT);
            }
            if (applicationMode == CharacterFactConfirmApplicationMode.HISTORY_ONLY) {
                suppressedPriorProposalSlots.add(slot);
            }
        }
    }

    private void validateComparisonRevision(
            List<SettingCandidate> candidates,
            String requestedRevision
    ) {
        List<String> revisions = candidates.stream()
                .filter(candidate -> !candidate.isCharacterDiscovery())
                .map(SettingCandidate::getCharacterComparisonBatch)
                .filter(Objects::nonNull)
                .map(batch -> batch.getAnalysisJob().getCharacterComparisonInputHash())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (revisions.isEmpty()) {
            return;
        }
        if (revisions.size() != 1 || !Objects.equals(revisions.getFirst(), requestedRevision)) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STALE);
        }
    }

    private String groupDecisionHash(SettingCandidateGroupConfirmRequest request) {
        StringBuilder canonical = new StringBuilder()
                .append(request.batchId()).append('|')
                .append(request.comparisonRevision()).append('\n');
        request.candidates().stream()
                .sorted(Comparator.comparing(SettingCandidateGroupConfirmDecision::candidateId))
                .forEach(decision -> canonical
                        .append(decision.candidateId()).append('|')
                        .append(decision.applicationMode()).append('|')
                        .append(decision.baseSnapshotVersion()).append('|')
                        .append(Boolean.TRUE.equals(decision.applyEditedValue())).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private List<UUID> comparisonDependencyIds(SettingCandidate candidate) {
        com.fasterxml.jackson.databind.JsonNode dependencies = candidate.getComparisonDependencyCandidateIds();
        if (dependencies == null || !dependencies.isArray()) {
            return List.of();
        }
        List<UUID> result = new ArrayList<>();
        Set<UUID> unique = new HashSet<>();
        for (com.fasterxml.jackson.databind.JsonNode dependency : dependencies) {
            try {
                UUID dependencyId = UUID.fromString(dependency.asText());
                if (!unique.add(dependencyId)) {
                    throw new IllegalArgumentException("duplicate dependency");
                }
                result.add(dependencyId);
            } catch (IllegalArgumentException exception) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_GROUP_DECISION_DEPENDENCY_CONFLICT);
            }
        }
        return List.copyOf(result);
    }

    private boolean changesCurrentSnapshot(CharacterFactOperation operation) {
        return operation == CharacterFactOperation.ADD
                || operation == CharacterFactOperation.UPDATE
                || operation == CharacterFactOperation.MERGE
                || operation == CharacterFactOperation.REMOVE;
    }

    private void validateComparisonNotProcessing(SettingCandidate candidate) {
        if (candidate.getComparisonStatus() == CharacterFactComparisonStatus.PROCESSING) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
        }
    }

    private void invalidateSelectedRuns(Work work, java.util.Collection<UUID> candidateIds) {
        validateAutomaticApplicationNotPending(work, candidateIds);
        Integer cutoff = settingCandidateRepository.findMutationSourceEpisodeNo(
                work.getId(), candidateIds, SettingCandidateReviewStatus.PENDING_REVIEW);
        if (cutoff != null) {
            analysisRunStateService.invalidateRunsForWorkForUpdate(
                    work.getId(), cutoff, "사용자가 설정 후보를 변경했습니다.");
        }
    }

    private void validateAutomaticApplicationNotPending(Work work, java.util.Collection<UUID> candidateIds) {
        // 자동 완료도 Work 잠금을 먼저 잡는다. 무효화하거나 후보를 잠그기 전에 원본 분석 상태를 확인한다.
        if (!candidateIds.isEmpty() && settingCandidateRepository
                .findPendingReviewSourceJobs(work.getId(), candidateIds).stream()
                .anyMatch(AnalysisJob::isAutomaticApplicationPending)) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_AUTOMATIC_APPLICATION_PENDING);
        }
    }

    private void enqueueComparisonJobIfNeeded(Long memberId, SettingCandidate candidate) {
        if (isOrdered(candidate)) {
            // 사용자 수정·연결 경로는 누적 입력을 무효화하며 hidden Job을 만들지 않는다.
            // 따라서 자동 처리가 없는 상태를 PENDING으로 표시하지 않는다.
            if (!candidate.isCharacterDiscovery() && candidate.isPendingReview()
                    && (candidate.getComparisonStatus() == CharacterFactComparisonStatus.PENDING
                    || candidate.getComparisonStatus() == CharacterFactComparisonStatus.WAITING_FOR_CHARACTER_MATCH)) {
                candidate.markRecomparisonRequired("누적 분석의 내용 또는 캐릭터 연결이 변경되었습니다. 변경된 내용으로 새 분석이 필요합니다.");
            }
            return;
        }
        characterComparisonJobCoordinator.enqueueIfNeeded(memberId, candidate);
    }

    private void enqueuePreviousScopes(
            Long memberId,
            List<CharacterFactComparisonJobCoordinator.ScopeRef> previousScopes,
            List<SettingCandidate> changedCandidates
    ) {
        Set<CharacterFactComparisonJobCoordinator.ScopeRef> currentScopes =
                new HashSet<>(characterComparisonJobCoordinator.scopeRefs(changedCandidates));
        characterComparisonJobCoordinator.enqueueScopes(
                memberId,
                previousScopes.stream().filter(scope -> !currentScopes.contains(scope)).toList()
        );
    }

    /**
     * 단건과 그룹 연결이 같은 검증과 상태 전이를 사용하게 한다.
     * 그룹 요청에서는 대상을 한 번만 조회·검증한 뒤 모든 후보에 적용해 한 트랜잭션의 결정으로 유지한다.
     */
    private void applyCharacterMatch(
            List<SettingCandidate> candidates,
            Work work,
            org.monitoring.catchholebackend.domain.character.type.SettingCandidateCharacterMatchResolutionType resolution,
            UUID matchedCharacterId,
            String requestedEntityName
    ) {
        switch (resolution) {
            case MATCH_EXISTING -> {
                if (matchedCharacterId == null) {
                    throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_REQUIRED);
                }
                WorkCharacter character = workCharacterRepository
                        .findByIdAndWorkIdForUpdate(matchedCharacterId, work.getId())
                        .orElseThrow(() -> new AppException(
                                CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID
                        ));
                if (character.getStatus() != CharacterStatus.ACTIVE) {
                    throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID);
                }
                candidates.forEach(candidate -> candidate.matchExistingCharacter(character));
            }
            case CREATE_NEW -> {
                String entityName = normalizeRequiredCharacterName(requestedEntityName);
                if (existsCharacterByGroupName(work.getId(), entityName)) {
                    throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED);
                }
                candidates.forEach(candidate -> candidate.markAsNewCharacter(entityName));
            }
        }
        candidates.forEach(SettingCandidate::recordUserModification);
    }

    private void validateCompletePendingGroup(
            Work work,
            UUID batchId,
            List<SettingCandidate> candidates,
            Set<UUID> requestedIds
    ) {
        if (candidates.size() != requestedIds.size()
                || candidates.stream().anyMatch(candidate -> !candidate.isPendingReview())) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT);
        }
        String selectedGroupKey = candidates.stream()
                .map(this::groupKey)
                .distinct()
                .reduce((first, second) -> {
                    throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT);
                })
                .orElseThrow(() -> new AppException(CharacterErrorCode.SETTING_CANDIDATE_NOT_FOUND));
        Set<UUID> pendingGroupIds = settingCandidateRepository.findReviewCandidates(
                        work.getId(),
                        batchId,
                        SettingCandidateReviewStatus.PENDING_REVIEW,
                        EnumSet.allOf(SettingCandidateMatchStatus.class)
                ).stream()
                .filter(candidate -> groupKey(candidate).equals(selectedGroupKey))
                .map(SettingCandidate::getId)
                .collect(java.util.stream.Collectors.toSet());
        if (!pendingGroupIds.equals(requestedIds)) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT);
        }
    }

    private SettingCandidate getCandidateInWork(UUID candidateId, Work work) {
        // 사용자 mutation도 Worker claim/complete와 동일한 candidate row lock을 잡아
        // PROCESSING/COMPLETED 상태를 마지막 flush가 덮어쓰는 경쟁을 막는다.
        return settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, work.getId())
                .orElseThrow(() -> new AppException(CharacterErrorCode.SETTING_CANDIDATE_NOT_FOUND));
    }

    private CandidateReviewContent resolveReviewContent(
            SettingCandidate candidate,
            String requestedAttributeName,
            String requestedAttributeValue,
            List<CharacterSettingSchema> schemas
    ) {
        SettingCandidateSchemaMatch currentMatch = settingCandidateSchemaResolver.resolve(
                candidate.getAttributeName(),
                candidate.getValueType(),
                schemas
        );
        boolean dynamic = isPatternMatch(currentMatch);
        String currentComparableAttributeName = dynamic
                ? normalizeStoredDynamicAttributeName(candidate.getAttributeName(), currentMatch.matchedSchema())
                : candidate.getAttributeName().trim();
        String nextAttributeName = dynamic
                ? resolveDynamicAttributeName(
                        requestedAttributeName,
                        candidate,
                        currentMatch,
                        schemas
                )
                : resolveFixedAttributeName(candidate, requestedAttributeName);
        String currentComparableAttributeValue = normalizeOptionalText(candidate.getAttributeValue());
        boolean semanticContentChanged = !currentComparableAttributeName.equals(nextAttributeName)
                || !Objects.equals(currentComparableAttributeValue, requestedAttributeValue);
        boolean storedContentNeedsNormalization =
                !candidate.getAttributeName().equals(nextAttributeName)
                        || !Objects.equals(candidate.getAttributeValue(), requestedAttributeValue);
        boolean storedValueNeedsRepair = characterSettingValueValidator.evaluateCandidate(
                candidate,
                currentMatch.matchedSchema().getFactType(),
                currentMatch.matchedSchema().getValueType()
        ).isInvalid();
        return new CandidateReviewContent(
                nextAttributeName,
                requestedAttributeValue,
                semanticContentChanged || storedValueNeedsRepair
                        ? rebuildValueJson(
                                candidate,
                                currentMatch,
                                nextAttributeName,
                                requestedAttributeValue,
                                dynamic
                        )
                        : candidate.getValueJson(),
                semanticContentChanged
                        || storedContentNeedsNormalization
                        || storedValueNeedsRepair
        );
    }

    private SettingCandidateResponse toResponse(
            SettingCandidate candidate,
            List<CharacterSettingSchema> schemas
    ) {
        CandidateResponseMetadata metadata = resolveResponseMetadata(candidate, schemas);
        return settingCandidateMapper.toResponse(
                candidate,
                metadata.attributeNameEditable(),
                metadata.attributeNamePrefix(),
                metadata.valueValidation()
        );
    }

    private SettingCandidateResponse toReviewListResponse(
            SettingCandidate candidate,
            List<CharacterSettingSchema> schemas
    ) {
        CandidateResponseMetadata metadata = resolveResponseMetadata(candidate, schemas);
        return settingCandidateMapper.toReviewListResponse(
                candidate,
                metadata.attributeNameEditable(),
                metadata.attributeNamePrefix(),
                metadata.valueValidation()
        );
    }

    private SettingCandidateGroupResponse toGroupResponse(
            String groupKey,
            List<SettingCandidate> candidates,
            List<CharacterSettingSchema> schemas
    ) {
        List<SettingCandidateResponse> responses = candidates.stream()
                .map(candidate -> toReviewListResponse(candidate, schemas))
                .toList();
        List<Integer> evidenceEpisodeNos = candidates.stream()
                .map(SettingCandidate::getEpisode)
                .filter(Objects::nonNull)
                .map(episode -> episode.getEpisodeNo())
                .distinct()
                .sorted()
                .toList();
        return new SettingCandidateGroupResponse(
                groupKey,
                SettingCandidateGroupNameNormalizer.toDisplayName(candidates.getFirst().getEntityName()),
                candidates.size(),
                evidenceEpisodeNos,
                responses
        );
    }

    private SettingCandidateGroupActionResponse toGroupActionResponse(
            String groupKey,
            List<SettingCandidate> candidates,
            List<CharacterSettingSchema> schemas
    ) {
        return new SettingCandidateGroupActionResponse(
                groupKey,
                SettingCandidateGroupNameNormalizer.toDisplayName(candidates.getFirst().getEntityName()),
                candidates.stream().map(candidate -> toResponse(candidate, schemas)).toList()
        );
    }

    private String groupKey(String entityName) {
        return SettingCandidateGroupNameNormalizer.toGroupKey(entityName);
    }

    private String groupKey(SettingCandidate candidate) {
        return candidate.getMatchedCharacterId() == null
                ? groupKey(candidate.getEntityName())
                : "existing:" + candidate.getMatchedCharacterId();
    }

    /** 후보 그룹과 동일한 대소문자·공백 정규화 규칙으로 기존 캐릭터를 찾는다. */
    private Optional<WorkCharacter> findActiveCharacterByGroupName(UUID workId, String entityName) {
        Optional<WorkCharacter> exactMatch = workCharacterRepository.findByWorkIdAndNameAndStatus(
                workId,
                SettingCandidateGroupNameNormalizer.toDisplayName(entityName),
                CharacterStatus.ACTIVE
        );
        if (exactMatch.isPresent()) {
            return exactMatch;
        }
        List<WorkCharacter> normalizedMatches = workCharacterRepository
                .findAllByWorkIdAndStatusOrderByCreatedAtDesc(
                        workId,
                        CharacterStatus.ACTIVE
                ).stream()
                .filter(character -> SettingCandidateGroupNameNormalizer.belongsToSameGroup(
                        character.getName(),
                        entityName
                ))
                .toList();
        if (normalizedMatches.size() > 1) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED);
        }
        return normalizedMatches.stream().findFirst();
    }

    private boolean existsCharacterByGroupName(UUID workId, String entityName) {
        String displayName = SettingCandidateGroupNameNormalizer.toDisplayName(entityName);
        if (workCharacterRepository.existsByWorkIdAndName(workId, displayName)) {
            return true;
        }
        return workCharacterRepository.findAllByWorkIdOrderByCreatedAtDesc(workId).stream()
                .anyMatch(character -> SettingCandidateGroupNameNormalizer.belongsToSameGroup(
                        character.getName(),
                        entityName
                ));
    }

    private boolean isUnknownCharacterName(String entityName) {
        String normalized = groupKey(entityName);
        return normalized.isBlank() || normalized.equals("미상");
    }

    private CandidateResponseMetadata resolveResponseMetadata(
            SettingCandidate candidate,
            List<CharacterSettingSchema> schemas
    ) {
        if (candidate.isCharacterDiscovery()) {
            return CandidateResponseMetadata.NOT_APPLICABLE;
        }
        try {
            SettingCandidateSchemaMatch match = settingCandidateSchemaResolver.resolve(
                    candidate.getAttributeName(),
                    candidate.getValueType(),
                    schemas
            );
            SettingCandidateValueValidation valueValidation =
                    characterSettingValueValidator.evaluateCandidate(
                            candidate,
                            match.matchedSchema().getFactType(),
                            match.matchedSchema().getValueType()
                    );
            if (!isPatternMatch(match)) {
                return new CandidateResponseMetadata(false, null, valueValidation);
            }
            return new CandidateResponseMetadata(
                    true,
                    dynamicPatternPrefix(match.matchedSchema()),
                    valueValidation
            );
        } catch (AppException exception) {
            if (exception.getResultCode() instanceof CharacterErrorCode errorCode) {
                return new CandidateResponseMetadata(
                        false,
                        null,
                        SettingCandidateValueValidation.unrepairableInvalid(errorCode)
                );
            }
            throw exception;
        }
    }

    private String resolveFixedAttributeName(SettingCandidate candidate, String requestedAttributeName) {
        String currentAttributeName = candidate.getAttributeName().trim();
        if (!currentAttributeName.equals(requestedAttributeName)) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_ATTRIBUTE_NAME_NOT_EDITABLE);
        }
        return currentAttributeName;
    }

    private String resolveDynamicAttributeName(
            String requestedAttributeName,
            SettingCandidate candidate,
            SettingCandidateSchemaMatch currentMatch,
            List<CharacterSettingSchema> schemas
    ) {
        String normalizedAttributeName =
                normalizeDynamicAttributeName(requestedAttributeName, currentMatch.matchedSchema());
        SettingCandidateSchemaMatch requestedMatch;
        try {
            requestedMatch = settingCandidateSchemaResolver.resolve(
                    normalizedAttributeName,
                    candidate.getValueType(),
                    schemas
            );
        } catch (AppException exception) {
            if (exception.getResultCode()
                    == CharacterErrorCode.SETTING_CANDIDATE_SCHEMA_MATCH_AMBIGUOUS) {
                throw exception;
            }
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_ATTRIBUTE_NAME_INVALID);
        }
        if (requestedMatch.matchedSchema() != currentMatch.matchedSchema()
                || !isPatternMatch(requestedMatch)) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_ATTRIBUTE_NAME_INVALID);
        }
        return normalizedAttributeName;
    }

    private String normalizeDynamicAttributeName(String attributeName, CharacterSettingSchema schema) {
        String prefix = dynamicPatternPrefix(schema);
        String trimmedAttributeName = attributeName.trim();
        if (!trimmedAttributeName.startsWith(prefix)) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_ATTRIBUTE_NAME_INVALID);
        }
        String suffix = trimmedAttributeName.substring(prefix.length()).trim();
        if (!StringUtils.hasText(suffix)
                || !StringUtils.hasText(suffix.replace('_', ' '))) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_ATTRIBUTE_NAME_INVALID);
        }
        return prefix + suffix.replaceAll("\\s+", "_");
    }

    private String normalizeStoredDynamicAttributeName(String attributeName, CharacterSettingSchema schema) {
        String prefix = dynamicPatternPrefix(schema);
        String trimmedAttributeName = attributeName.trim();
        String suffix = trimmedAttributeName.substring(prefix.length()).trim();
        return prefix + suffix.replaceAll("\\s+", "_");
    }

    private String dynamicPatternPrefix(CharacterSettingSchema schema) {
        String pattern = schema.getAttributePattern();
        if (pattern == null || !pattern.trim().endsWith(".*")) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_ATTRIBUTE_NAME_INVALID);
        }
        String trimmedPattern = pattern.trim();
        return trimmedPattern.substring(0, trimmedPattern.length() - 1);
    }

    private boolean isPatternMatch(SettingCandidateSchemaMatch match) {
        return !match.factKey().equals(match.matchedSchema().getSchemaKey().trim());
    }

    private JsonNode rebuildValueJson(
            SettingCandidate candidate,
            SettingCandidateSchemaMatch schemaMatch,
            String attributeName,
            String attributeValue,
            boolean dynamic
    ) {
        ObjectNode valueJson = objectMapper.createObjectNode();
        if (candidate.getValueType() == SettingValueType.JSON) {
            valueJson.put("name", resolveStructuredName(schemaMatch, attributeName, dynamic));
            return valueJson;
        }

        JsonNode scalarValue = toScalarValueNode(candidate, attributeValue);
        validateCoreEditedValue(schemaMatch.matchedSchema().getFactType(), scalarValue);
        valueJson.set("value", scalarValue);
        if (dynamic) {
            valueJson.put("name", dynamicDisplayName(schemaMatch.matchedSchema(), attributeName));
        }
        return valueJson;
    }

    private JsonNode toScalarValueNode(SettingCandidate candidate, String attributeValue) {
        if (attributeValue == null) {
            return NullNode.getInstance();
        }
        return switch (candidate.getValueType()) {
            case STRING, UNKNOWN -> objectMapper.getNodeFactory().textNode(attributeValue);
            case NUMBER -> toNumberNode(attributeValue);
            case BOOLEAN -> toBooleanNode(attributeValue);
            case JSON -> throw new IllegalStateException("JSON 후보는 scalar value로 변환할 수 없습니다.");
        };
    }

    private JsonNode toNumberNode(String attributeValue) {
        try {
            return objectMapper.getNodeFactory().numberNode(new BigDecimal(attributeValue));
        } catch (NumberFormatException exception) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_FORMAT_INVALID);
        }
    }

    private JsonNode toBooleanNode(String attributeValue) {
        if (attributeValue.equals("true")) {
            return objectMapper.getNodeFactory().booleanNode(true);
        }
        if (attributeValue.equals("false")) {
            return objectMapper.getNodeFactory().booleanNode(false);
        }
        throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_FORMAT_INVALID);
    }

    private void validateCoreEditedValue(CharacterFactType factType, JsonNode valueNode) {
        if (factType != CharacterFactType.AGE && factType != CharacterFactType.LEVEL) {
            return;
        }
        if (valueNode == null || !valueNode.isNumber()) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_INVALID);
        }
        try {
            if (valueNode.decimalValue().intValueExact() < 0) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_INVALID);
            }
        } catch (ArithmeticException exception) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_INVALID);
        }
    }

    private String resolveStructuredName(
            SettingCandidateSchemaMatch schemaMatch,
            String attributeName,
            boolean dynamic
    ) {
        if (dynamic) {
            return dynamicDisplayName(schemaMatch.matchedSchema(), attributeName);
        }
        return schemaMatch.matchedSchema().getDisplayName().trim();
    }

    private String dynamicDisplayName(CharacterSettingSchema schema, String attributeName) {
        String prefix = dynamicPatternPrefix(schema);
        return attributeName.substring(prefix.length())
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeRequiredText(String value) {
        return value.trim();
    }

    private String normalizeRequiredCharacterName(String value) {
        if (!StringUtils.hasText(value)) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_NEW_CHARACTER_NAME_REQUIRED);
        }
        return SettingCandidateGroupNameNormalizer.toDisplayName(value);
    }

    private String normalizeOptionalText(String value) {
        return value == null ? null : value.trim();
    }

    private record CandidateReviewContent(
            String attributeName,
            String attributeValue,
            JsonNode valueJson,
            boolean updateRequired
    ) {
    }

    private record CandidateResponseMetadata(
            boolean attributeNameEditable,
            String attributeNamePrefix,
            SettingCandidateValueValidation valueValidation
    ) {
        private static final CandidateResponseMetadata NOT_APPLICABLE =
                new CandidateResponseMetadata(
                        false,
                        null,
                        SettingCandidateValueValidation.notApplicable()
                );
    }
}
