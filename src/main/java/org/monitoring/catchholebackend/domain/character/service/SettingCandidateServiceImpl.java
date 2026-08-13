package org.monitoring.catchholebackend.domain.character.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobEpisodeRange;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
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
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
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
public class SettingCandidateServiceImpl implements SettingCandidateService {

    private final WorkRepository workRepository;
    private final UploadBatchRepository uploadBatchRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final SettingCandidateRepository settingCandidateRepository;
    private final WorkCharacterRepository workCharacterRepository;
    private final CharacterSettingSchemaRepository characterSettingSchemaRepository;
    private final SettingCandidateMapper settingCandidateMapper;
    private final SettingCandidatePromotionService settingCandidatePromotionService;
    private final CharacterFactComparisonWorkerService characterFactComparisonWorkerService;
    private final SettingCandidateSchemaResolver settingCandidateSchemaResolver;
    private final AiTokenService aiTokenService;
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
                SettingCandidateMatchStatus.AMBIGUOUS
        );
        AnalysisJobEpisodeRange episodeRange =
                analysisJobRepository.findEpisodeRangeByWorkIdAndBatchId(work.getId(), batchId);
        List<CharacterSettingSchema> schemas =
                characterSettingSchemaRepository.findAllActiveForWork(work.getId());

        Map<String, List<SettingCandidate>> candidatesByGroup = new LinkedHashMap<>();
        candidates.forEach(candidate -> candidatesByGroup
                .computeIfAbsent(groupKey(candidate.getEntityName()), ignored -> new ArrayList<>())
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
        if (reviewContent.updateRequired()) {
            candidate.updateReviewContent(
                    reviewContent.attributeName(),
                    reviewContent.attributeValue(),
                    reviewContent.valueJson()
            );
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
        List<SettingCandidate> candidates = settingCandidateRepository.findAllByIdsAndBatchForUpdate(
                work.getId(), request.batchId(), requestedIds
        );
        validateCompletePendingGroup(work, request.batchId(), candidates, requestedIds);
        candidates.forEach(candidate -> {
            validateComparisonNotProcessing(candidate);
        });
        applyCharacterMatch(
                candidates,
                work,
                request.resolutionType(),
                request.matchedCharacterId(),
                request.entityName()
        );
        candidates.forEach(candidate -> enqueueComparisonJobIfNeeded(memberId, candidate));
        settingCandidateRepository.flush();
        List<CharacterSettingSchema> schemas = characterSettingSchemaRepository.findAllActiveForWork(work.getId());
        return toGroupActionResponse(groupKey(candidates.getFirst().getEntityName()), candidates, schemas);
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
        SettingCandidate candidate = getCandidateInWork(candidateId, work);
        candidate.validateEditable();
        validateComparisonNotProcessing(candidate);

        // 사용자가 기존 캐릭터를 지정하면 즉시 MATCHED로, 신규로 판단하면 confirm 전까지 UNRESOLVED로 둔다.
        applyCharacterMatch(
                List.of(candidate),
                work,
                request.resolutionType(),
                request.matchedCharacterId(),
                request.entityName()
        );
        enqueueComparisonJobIfNeeded(memberId, candidate);

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
        SettingCandidate candidate = getCandidateInWork(candidateId, work);
        if (candidate.getReviewStatus() == SettingCandidateReviewStatus.CONFIRMED) {
            return SettingCandidateConfirmResult.confirmed(
                    settingCandidateMapper.toReviewStatusResponse(candidate)
            );
        }

        if (prepareUnresolvedExistingCharacterForComparison(memberId, candidate, work)) {
            return SettingCandidateConfirmResult.recomparisonRequired(
                    settingCandidateMapper.toReviewStatusResponse(candidate)
            );
        }

        // Java-first 순차 배포 중 구버전 AI가 남긴 MATCHED+NOT_REQUIRED 후보는 확정을 우회하지 않고
        // 신규 hidden 비교 Job으로 복구한다.
        if (!candidate.isCharacterDiscovery()
                && candidate.getMatchedCharacterId() != null
                && candidate.getComparisonStatus() == CharacterFactComparisonStatus.NOT_REQUIRED) {
            candidate.requestComparison();
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
        validateConfirmPolicy(candidate, applicationMode, request == null ? null : request.baseSnapshotVersion());
        if (applicationMode == CharacterFactConfirmApplicationMode.APPLY_PROPOSAL
                && !candidate.isCharacterDiscovery()
                && candidate.getMatchStatus() != SettingCandidateMatchStatus.UNRESOLVED
                && !characterFactComparisonWorkerService.hasCurrentContext(candidate)) {
            candidate.requestComparison();
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
        List<SettingCandidate> candidates = settingCandidateRepository.findAllByIdsAndBatchForUpdate(
                work.getId(), request.batchId(), decisions.keySet()
        );
        validateCompletePendingGroup(work, request.batchId(), candidates, decisions.keySet());
        if (candidates.stream().anyMatch(candidate -> candidate.getMatchStatus()
                == SettingCandidateMatchStatus.AMBIGUOUS)) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCH_STATUS_CONFLICT);
        }

        // 같은 이름의 기존 캐릭터가 확인되면 모든 UNRESOLVED 행을 먼저 연결한다.
        // 연결 전 문맥으로는 확정하지 않고 그룹 전체를 숨김 비교 Worker에 다시 맡긴다.
        List<SettingCandidate> unresolved = candidates.stream()
                .filter(candidate -> candidate.getMatchStatus() == SettingCandidateMatchStatus.UNRESOLVED)
                .toList();
        if (!unresolved.isEmpty()) {
            String entityName = SettingCandidateGroupNameNormalizer.toDisplayName(
                    unresolved.getFirst().getEntityName()
            );
            WorkCharacter existing = workCharacterRepository.findByWorkIdAndNameAndStatus(
                            work.getId(),
                            entityName,
                            CharacterStatus.ACTIVE
                    )
                    .orElse(null);
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
            if (workCharacterRepository.existsByWorkIdAndName(work.getId(), entityName)) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED);
            }
            if (unresolved.size() != candidates.size()) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCH_STATUS_CONFLICT);
            }
            List<SettingCandidateGroupPromotion> promotions = candidates.stream()
                    .map(candidate -> new SettingCandidateGroupPromotion(
                            candidate,
                            decisions.get(candidate.getId()).applicationMode()
                    ))
                    .toList();
            settingCandidatePromotionService.promoteNewCharacterGroup(promotions);
            List<CharacterSettingSchema> schemas = characterSettingSchemaRepository.findAllActiveForWork(work.getId());
            return SettingCandidateGroupConfirmResult.confirmed(
                    toGroupActionResponse(groupKey(entityName), candidates, schemas)
            );
        }

        List<UUID> bootstrapped = new ArrayList<>();
        for (SettingCandidate candidate : candidates) {
            if (!candidate.isCharacterDiscovery()
                    && candidate.getMatchedCharacterId() != null
                    && candidate.getComparisonStatus() == CharacterFactComparisonStatus.NOT_REQUIRED) {
                candidate.requestComparison();
                enqueueComparisonJobIfNeeded(memberId, candidate);
                bootstrapped.add(candidate.getId());
            }
        }
        if (!bootstrapped.isEmpty()) {
            settingCandidateRepository.flush();
            return SettingCandidateGroupConfirmResult.recomparisonRequired(bootstrapped);
        }

        // 한 행을 반영한 결과가 다음 행의 기존 context hash를 바꾸기 전에 그룹 전체를 먼저 검증한다.
        for (SettingCandidate candidate : candidates) {
            SettingCandidateGroupConfirmDecision decision = decisions.get(candidate.getId());
            // EXCLUDE는 현재값이나 이력을 만들지 않는 것도 하나의 AI 제안이다. 그룹 전체 확정에서는
            // 사용자가 그 제안을 승인한 것으로 보고 아래 반영 단계에서 자동 무시 처리한다.
            if (candidate.getSuggestedOperation() == CharacterFactOperation.EXCLUDE) {
                continue;
            }
            validateConfirmPolicy(candidate, decision.applicationMode(), decision.baseSnapshotVersion());
            if (decision.applicationMode() == CharacterFactConfirmApplicationMode.APPLY_PROPOSAL
                    && !candidate.isCharacterDiscovery()
                    && !characterFactComparisonWorkerService.hasCurrentContext(candidate)) {
                candidate.requestComparison();
                enqueueComparisonJobIfNeeded(memberId, candidate);
                bootstrapped.add(candidate.getId());
            }
        }
        if (!bootstrapped.isEmpty()) {
            settingCandidateRepository.flush();
            return SettingCandidateGroupConfirmResult.recomparisonRequired(bootstrapped);
        }

        List<CharacterSettingSchema> schemas = characterSettingSchemaRepository.findAllActiveForWork(work.getId());
        validateGroupDecisionDependencies(candidates, decisions, schemas);

        for (SettingCandidate candidate : candidates) {
            if (candidate.getSuggestedOperation() == CharacterFactOperation.EXCLUDE) {
                candidate.dismiss();
                continue;
            }
            if (candidate.confirm()) {
                settingCandidatePromotionService.promote(
                        candidate,
                        decisions.get(candidate.getId()).applicationMode()
                );
            }
        }
        return SettingCandidateGroupConfirmResult.confirmed(
                toGroupActionResponse(groupKey(candidates.getFirst().getEntityName()), candidates, schemas)
        );
    }

    @Override
    @Transactional
    public SettingCandidateResponse retryComparison(Long memberId, UUID workId, UUID candidateId) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        SettingCandidate candidate = getCandidateInWork(candidateId, work);
        candidate.validateReviewContentEditable();
        validateComparisonNotProcessing(candidate);
        if (candidate.getMatchedCharacterId() == null) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCH_STATUS_CONFLICT);
        }
        if (candidate.getComparisonStatus() == CharacterFactComparisonStatus.COMPLETED) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
        }
        candidate.requestComparison();
        enqueueComparisonJobIfNeeded(memberId, candidate);
        List<CharacterSettingSchema> schemas = characterSettingSchemaRepository.findAllActiveForWork(work.getId());
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
        SettingCandidate candidate = getCandidateInWork(candidateId, work);
        candidate.dismiss();
        return settingCandidateMapper.toReviewStatusResponse(candidate);
    }

    private boolean prepareUnresolvedExistingCharacterForComparison(
            Long memberId,
            SettingCandidate candidate,
            Work work
    ) {
        if (candidate.isCharacterDiscovery()
                || candidate.getMatchStatus() != SettingCandidateMatchStatus.UNRESOLVED
                || candidate.getMatchedCharacterId() != null) {
            return false;
        }
        WorkCharacter existing = workCharacterRepository.findByWorkIdAndNameAndStatus(
                work.getId(),
                candidate.getEntityName().trim(),
                CharacterStatus.ACTIVE
        ).orElse(null);
        if (existing == null) {
            if (workCharacterRepository.existsByWorkIdAndName(
                    work.getId(),
                    candidate.getEntityName().trim()
            )) {
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
        candidate.matchExistingCharacter(locked);
        enqueueComparisonJobIfNeeded(memberId, candidate);
        settingCandidateRepository.flush();
        return true;
    }

    private void validateConfirmPolicy(
            SettingCandidate candidate,
            CharacterFactConfirmApplicationMode applicationMode,
            Long requestedBaseSnapshotVersion
    ) {
        if (candidate.isCharacterDiscovery()
                || candidate.getMatchStatus() == SettingCandidateMatchStatus.UNRESOLVED) {
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
     * 뒤 후보의 비교 결과가 앞선 동일 slot 후보의 제안값을 문맥으로 사용했는데 사용자가 그 앞 후보를
     * HISTORY_ONLY로 바꾸면, 뒤 제안만 적용할 때 검증하지 않은 현재값이 만들어진다. 그룹 확정은 원자적으로
     * 중단해 사용자가 두 후보의 반영 방식을 일관되게 다시 선택하도록 한다.
     */
    private void validateGroupDecisionDependencies(
            List<SettingCandidate> candidates,
            Map<UUID, SettingCandidateGroupConfirmDecision> decisions,
            List<CharacterSettingSchema> schemas
    ) {
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
                    schemaMatch.factKey()
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

    private boolean changesCurrentSnapshot(CharacterFactOperation operation) {
        return operation == CharacterFactOperation.ADD
                || operation == CharacterFactOperation.UPDATE
                || operation == CharacterFactOperation.MERGE;
    }

    private void validateComparisonNotProcessing(SettingCandidate candidate) {
        if (candidate.getComparisonStatus() == CharacterFactComparisonStatus.PROCESSING) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
        }
    }

    private void enqueueComparisonJobIfNeeded(Long memberId, SettingCandidate candidate) {
        if (candidate.isCharacterDiscovery()
                || candidate.getMatchedCharacterId() == null
                || candidate.getComparisonStatus() != CharacterFactComparisonStatus.PENDING) {
            return;
        }
        // 사용자 mutation으로 다시 PENDING이 된 후보는 원 분석 Job의 drain/checkpoint 시점과 무관하게
        // 항상 후보 전용 hidden Job에 위임한다. 원 Job claim/query는 active hidden Job이 있는 후보를 제외한다.
        // 사용자 mutation은 이미 Work -> candidate 순서로 잠겼다. 여기서 Job까지 역순으로
        // pessimistic lock하면 Job -> candidate 순서인 Worker와 deadlock할 수 있으므로 읽기만 한다.
        // 정상 생성 경로는 위 잠금으로 직렬화되고 DB partial unique index가 최종 중복을 방어한다.
        if (analysisJobRepository.existsBySettingCandidateIdAndStatusIn(
                candidate.getId(),
                List.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.RUNNING)
        )) {
            return;
        }
        aiTokenService.ensureAnalysisCanStart(memberId);
        analysisJobRepository.save(AnalysisJob.createCharacterFactComparison(candidate));
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
                if (workCharacterRepository.existsByWorkIdAndName(work.getId(), entityName)) {
                    throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED);
                }
                candidates.forEach(candidate -> candidate.markAsNewCharacter(entityName));
            }
        }
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
                .map(candidate -> groupKey(candidate.getEntityName()))
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
                .filter(candidate -> groupKey(candidate.getEntityName()).equals(selectedGroupKey))
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
        boolean storedCoreScalarNeedsRepair = hasIncompatibleCoreScalarValueJson(
                candidate,
                currentMatch
        );
        return new CandidateReviewContent(
                nextAttributeName,
                requestedAttributeValue,
                semanticContentChanged || storedCoreScalarNeedsRepair
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
                        || storedCoreScalarNeedsRepair
        );
    }

    /**
     * AGE/LEVEL의 숨은 대표값이 존재하지만 숫자가 아니면 같은 표시값 저장도 typed envelope로 수리한다.
     * 대표값 자체가 없거나 이미 숫자인 rich JSON은 기존 근거와 함께 no-op으로 보존한다.
     */
    private boolean hasIncompatibleCoreScalarValueJson(
            SettingCandidate candidate,
            SettingCandidateSchemaMatch schemaMatch
    ) {
        CharacterFactType factType = schemaMatch.matchedSchema().getFactType();
        if (factType != CharacterFactType.AGE && factType != CharacterFactType.LEVEL) {
            return false;
        }

        JsonNode valueNode = candidate.getValueJson();
        if (valueNode == null || valueNode.isNull()) {
            return false;
        }
        if (valueNode.isObject()) {
            valueNode = valueNode.get("value");
        }
        return valueNode != null && !valueNode.isNull() && !valueNode.isNumber();
    }

    private SettingCandidateResponse toResponse(
            SettingCandidate candidate,
            List<CharacterSettingSchema> schemas
    ) {
        AttributeNameEditMetadata metadata = resolveAttributeNameEditMetadata(candidate, schemas);
        return settingCandidateMapper.toResponse(
                candidate,
                metadata.attributeNameEditable(),
                metadata.attributeNamePrefix()
        );
    }

    private SettingCandidateResponse toReviewListResponse(
            SettingCandidate candidate,
            List<CharacterSettingSchema> schemas
    ) {
        AttributeNameEditMetadata metadata = resolveAttributeNameEditMetadata(candidate, schemas);
        return settingCandidateMapper.toReviewListResponse(
                candidate,
                metadata.attributeNameEditable(),
                metadata.attributeNamePrefix()
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

    private boolean isUnknownCharacterName(String entityName) {
        String normalized = groupKey(entityName);
        return normalized.isBlank() || normalized.equals("미상");
    }

    private AttributeNameEditMetadata resolveAttributeNameEditMetadata(
            SettingCandidate candidate,
            List<CharacterSettingSchema> schemas
    ) {
        if (candidate.isCharacterDiscovery()) {
            return AttributeNameEditMetadata.NOT_EDITABLE;
        }
        try {
            SettingCandidateSchemaMatch match = settingCandidateSchemaResolver.resolve(
                    candidate.getAttributeName(),
                    candidate.getValueType(),
                    schemas
            );
            if (!isPatternMatch(match)) {
                return AttributeNameEditMetadata.NOT_EDITABLE;
            }
            return new AttributeNameEditMetadata(
                    true,
                    dynamicPatternPrefix(match.matchedSchema())
            );
        } catch (AppException exception) {
            return AttributeNameEditMetadata.NOT_EDITABLE;
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
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_EDIT_VALUE_INVALID);
        }
    }

    private JsonNode toBooleanNode(String attributeValue) {
        if (attributeValue.equalsIgnoreCase("true")) {
            return objectMapper.getNodeFactory().booleanNode(true);
        }
        if (attributeValue.equalsIgnoreCase("false")) {
            return objectMapper.getNodeFactory().booleanNode(false);
        }
        throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_EDIT_VALUE_INVALID);
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

    private record AttributeNameEditMetadata(
            boolean attributeNameEditable,
            String attributeNamePrefix
    ) {
        private static final AttributeNameEditMetadata NOT_EDITABLE =
                new AttributeNameEditMetadata(false, null);
    }
}
