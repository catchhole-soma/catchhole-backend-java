package org.monitoring.catchholebackend.domain.character.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobLeaseService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.dto.request.WorkerCharacterFactComparisonCompleteRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.WorkerCharacterFactComparisonFailRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.WorkerCharacterFactComparisonCandidatePayload;
import org.monitoring.catchholebackend.domain.character.dto.response.WorkerCharacterFactComparisonContextResponse;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterFactComparisonWorkerMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSettingValueValidator;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateSchemaMatch;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateSchemaResolver;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateChronology;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSnapshotSourceRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CharacterFactComparisonWorkerServiceImpl implements CharacterFactComparisonWorkerService {

    private static final int CLAIM_SIZE = 1;
    private static final int MAX_CONTEXT_ENTRIES = 30;
    private static final int MAX_PRIOR_CANDIDATES = 30;

    private final AnalysisJobLeaseService analysisJobLeaseService;
    private final SettingCandidateRepository settingCandidateRepository;
    private final WorkCharacterRepository workCharacterRepository;
    private final CharacterSettingSchemaRepository schemaRepository;
    private final CharacterSnapshotSourceRepository snapshotSourceRepository;
    private final SettingCandidateSchemaResolver schemaResolver;
    private final CharacterSnapshotAccessor snapshotAccessor;
    private final CharacterSettingValueValidator valueValidator;
    private final CharacterFactComparisonWorkerMapper workerMapper;
    // 이 프로젝트는 전역 ObjectMapper bean을 강제하지 않으므로 비교 문맥 hash/JSON 변환 전용 mapper를 둔다.
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Override
    @Transactional
    public Optional<WorkerCharacterFactComparisonCandidatePayload> claimNextCharacterFactComparison(
            UUID analysisJobId,
            UUID leaseToken
    ) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        SettingCandidate candidate;
        if (analysisJob.getJobType() == AnalysisJobType.SETTING_EXTRACTION) {
            if (!analysisJob.hasReachedCheckpoint(AnalysisJobCheckpointStage.CHARACTER_CANDIDATES_SAVED)) {
                throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_CHECKPOINT_INCOMPLETE);
            }
            candidate = settingCandidateRepository.findComparisonClaimCandidates(
                    analysisJobId,
                    SettingCandidateReviewStatus.PENDING_REVIEW,
                    CharacterFactComparisonStatus.PENDING,
                    PageRequest.of(0, CLAIM_SIZE)
            ).stream().findFirst().orElse(null);
        } else if (analysisJob.getJobType() == AnalysisJobType.CHARACTER_FACT_COMPARISON) {
            candidate = lockLinkedCandidate(analysisJob);
            if (candidate.getComparisonStatus() != CharacterFactComparisonStatus.PENDING) {
                return Optional.empty();
            }
        } else {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_WORKER_JOB_INVALID);
        }
        if (candidate == null) {
            return Optional.empty();
        }
        candidate.startComparison();
        CanonicalTarget target = resolveCanonicalTarget(candidate);
        WorkCharacter character = getMatchedCharacter(candidate, false);
        return Optional.of(workerMapper.toCandidatePayload(
                candidate,
                character.getName(),
                target.factType(),
                target.factKey()
        ));
    }

    @Override
    @Transactional
    public WorkerCharacterFactComparisonContextResponse getCharacterFactComparisonContext(
            UUID analysisJobId,
            UUID candidateId,
            UUID leaseToken
    ) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        SettingCandidate candidate = getOwnedProcessingCandidate(analysisJob, candidateId);
        CanonicalTarget target = resolveCanonicalTarget(candidate);
        WorkCharacter character = getMatchedCharacter(candidate, true);
        ContextSnapshot context = buildContext(candidate, character, target);
        candidate.recordComparisonContext(character.getSnapshotVersion(), context.contextToken());
        return new WorkerCharacterFactComparisonContextResponse(
                workerMapper.toCandidatePayload(
                        candidate,
                        character.getName(),
                        target.factType(),
                        target.factKey()
                ),
                context.entries(),
                context.priorCandidates(),
                context.contextToken()
        );
    }

    @Override
    @Transactional
    public void completeCharacterFactComparison(
            UUID analysisJobId,
            UUID candidateId,
            UUID leaseToken,
            WorkerCharacterFactComparisonCompleteRequest request
    ) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        SettingCandidate candidate = getOwnedProcessingCandidate(analysisJob, candidateId);
        CanonicalTarget canonicalTarget = resolveCanonicalTarget(candidate);
        WorkCharacter character = getMatchedCharacter(candidate, true);
        ContextSnapshot currentContext = buildContext(candidate, character, canonicalTarget);
        validateFreshContext(candidate, character, currentContext, request.contextToken());

        Map<CharacterSnapshotSlot, CharacterSnapshotEntry> currentSnapshot = snapshotAccessor.read(character);
        CharacterSnapshotSlot targetSlot = validateTarget(canonicalTarget, request);
        Set<CharacterSnapshotSlot> contextSlots = currentContext.entries().stream()
                .map(entry -> new CharacterSnapshotSlot(entry.factType(), entry.factKey()))
                .collect(java.util.stream.Collectors.toSet());
        List<CharacterSnapshotSlot> removedSlots = validateRemovedEntries(
                currentSnapshot,
                contextSlots,
                targetSlot,
                request
        );
        validateOperation(canonicalTarget, currentSnapshot, targetSlot, removedSlots, request);

        JsonNode proposedValueJson = toNullableJsonNode(request.proposedValueJson());
        if (request.operation() == CharacterFactOperation.ADD
                || request.operation() == CharacterFactOperation.UPDATE
                || request.operation() == CharacterFactOperation.MERGE) {
            valueValidator.validateProposal(
                    proposedValueJson,
                    canonicalTarget.factType(),
                    canonicalTarget.valueType()
            );
        }
        JsonNode removedEntriesJson = objectMapper.valueToTree(request.removedSnapshotEntries());
        candidate.completeComparison(
                request.operation(),
                targetSlot == null ? null : targetSlot.factType(),
                targetSlot == null ? null : targetSlot.factKey(),
                request.proposedFactValue(),
                proposedValueJson,
                removedEntriesJson,
                request.temporalScope(),
                request.comparisonReason(),
                objectMapper.valueToTree(request.rawComparisonJson()),
                LocalDateTime.now()
        );
    }

    @Override
    @Transactional
    public void failCharacterFactComparison(
            UUID analysisJobId,
            UUID candidateId,
            UUID leaseToken,
            WorkerCharacterFactComparisonFailRequest request
    ) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        getOwnedProcessingCandidate(analysisJob, candidateId).failComparison(request.errorMessage());
    }

    @Override
    @Transactional
    public boolean hasCurrentContext(SettingCandidate candidate) {
        if (candidate.getComparisonContextHash() == null || candidate.getMatchedCharacterId() == null) {
            return false;
        }
        CanonicalTarget target = resolveCanonicalTarget(candidate);
        WorkCharacter character = getMatchedCharacter(candidate, true);
        return Objects.equals(
                candidate.getComparisonContextHash(),
                buildContext(candidate, character, target).contextToken()
        );
    }

    private ContextSnapshot buildContext(
            SettingCandidate candidate,
            WorkCharacter character,
            CanonicalTarget target
    ) {
        List<CharacterSnapshotSource> sources =
                snapshotSourceRepository.findAllByWorkCharacterIdOrderByFactTypeAscFactKeyAscSourceOrderAsc(
                        character.getId()
                );
        Map<CharacterSnapshotSlot, List<org.monitoring.catchholebackend.domain.character.entity.CharacterFact>>
                sourceFactsBySlot = new HashMap<>();
        Map<CharacterSnapshotSlot, List<UUID>> sourceIdsBySlot = new HashMap<>();
        for (CharacterSnapshotSource source : sources) {
            CharacterSnapshotSlot slot = new CharacterSnapshotSlot(source.getFactType(), source.getFactKey());
            sourceFactsBySlot.computeIfAbsent(slot, ignored -> new ArrayList<>())
                    .add(source.getSourceFact());
            sourceIdsBySlot.computeIfAbsent(slot, ignored -> new ArrayList<>())
                    .add(source.getSourceFact().getId());
        }
        Map<CharacterSnapshotSlot, CharacterSnapshotEntry> snapshot =
                snapshotAccessor.read(character, sourceFactsBySlot);
        CharacterSnapshotSlot exactSlot = new CharacterSnapshotSlot(target.factType(), target.factKey());
        List<CharacterSnapshotEntry> selected = new ArrayList<>();

        // exact slot은 정렬이나 30건 제한에 밀리지 않도록 항상 첫 번째에 둔다.
        CharacterSnapshotEntry exactEntry = snapshot.get(exactSlot);
        if (exactEntry != null) {
            selected.add(exactEntry);
        }
        // 독립 slot끼리 불필요한 stale/recompare를 만들지 않는다. 서로 종료 관계를 판단하는 STATUS만
        // 같은 타입의 현재 상태를 함께 제공해 회복/해제 시 제거 제안을 만들 수 있게 한다.
        if (target.factType() == CharacterFactType.STATUS) {
            snapshot.values().stream()
                    .filter(entry -> entry.slot().factType() == CharacterFactType.STATUS)
                    .filter(entry -> !entry.slot().equals(exactSlot))
                    // DB에 가장 최근 생성된 source Fact가 있는 상태를 우선하고,
                    // 생성 시각이 같거나 없는 legacy slot은 factKey로 순서를 고정한다.
                    .sorted(Comparator
                            .comparing(
                                    (CharacterSnapshotEntry entry) -> latestSourceCreatedAt(
                                            entry.slot(),
                                            sourceFactsBySlot
                                    ),
                                    Comparator.nullsLast(Comparator.reverseOrder())
                            )
                            .thenComparing(entry -> entry.slot().factKey()))
                    .limit(MAX_CONTEXT_ENTRIES - selected.size())
                    .forEach(selected::add);
        }

        List<WorkerCharacterFactComparisonContextResponse.SnapshotEntry> responseEntries = selected.stream()
                .map(entry -> new WorkerCharacterFactComparisonContextResponse.SnapshotEntry(
                        entry.slot().factType(),
                        entry.slot().factKey(),
                        entry.factValue(),
                        workerMapper.toJsonValue(entry.valueJson())
                ))
                .toList();
        List<WorkerCharacterFactComparisonContextResponse.PriorCandidate> priorCandidates =
                findPriorCandidates(candidate, character, target);
        return new ContextSnapshot(
                responseEntries,
                priorCandidates,
                fingerprint(candidate, character, target, selected, sourceIdsBySlot, priorCandidates)
        );
    }

    private List<WorkerCharacterFactComparisonContextResponse.PriorCandidate> findPriorCandidates(
            SettingCandidate candidate,
            WorkCharacter character,
            CanonicalTarget target
    ) {
        if (candidate.getAnalysisJob() == null || candidate.getAnalysisJob().getBatch() == null) {
            return List.of();
        }
        List<SettingCandidate> chronology = new ArrayList<>(
                settingCandidateRepository.findPendingComparisonChronology(
                        candidate.getWork().getId(),
                        candidate.getAnalysisJob().getBatch().getId(),
                        character.getId(),
                        SettingCandidateReviewStatus.PENDING_REVIEW
                )
        );
        chronology = SettingCandidateChronology.sorted(chronology);

        List<WorkerCharacterFactComparisonContextResponse.PriorCandidate> matching = new ArrayList<>();
        for (SettingCandidate prior : chronology) {
            if (prior.getId().equals(candidate.getId())) {
                break;
            }
            CanonicalTarget priorTarget = resolveCanonicalTarget(prior);
            if (priorTarget.factType() != target.factType()
                    || !priorTarget.factKey().equals(target.factKey())) {
                continue;
            }
            matching.add(toPriorCandidate(prior));
        }
        int fromIndex = Math.max(0, matching.size() - MAX_PRIOR_CANDIDATES);
        return List.copyOf(matching.subList(fromIndex, matching.size()));
    }

    private WorkerCharacterFactComparisonContextResponse.PriorCandidate toPriorCandidate(
            SettingCandidate candidate
    ) {
        return new WorkerCharacterFactComparisonContextResponse.PriorCandidate(
                candidate.getEpisode() == null ? null : candidate.getEpisode().getEpisodeNo(),
                candidate.getAttributeName(),
                candidate.getAttributeValue(),
                workerMapper.toJsonValue(candidate.getValueJson()),
                workerMapper.toEvidenceSpans(candidate.getEvidenceSpans()),
                candidate.getComparisonStatus(),
                candidate.getSuggestedOperation(),
                candidate.getProposedFactValue(),
                workerMapper.toJsonValue(candidate.getProposedValueJson())
        );
    }

    private LocalDateTime latestSourceCreatedAt(
            CharacterSnapshotSlot slot,
            Map<CharacterSnapshotSlot, List<CharacterFact>> sourceFactsBySlot
    ) {
        return sourceFactsBySlot.getOrDefault(slot, List.of()).stream()
                .map(CharacterFact::getCreatedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
    }

    private void validateFreshContext(
            SettingCandidate candidate,
            WorkCharacter character,
            ContextSnapshot currentContext,
            String requestToken
    ) {
        if (candidate.getComparisonBaseSnapshotVersion() == null
                || candidate.getComparisonContextHash() == null
                || !Objects.equals(candidate.getComparisonContextHash(), requestToken)
                || !Objects.equals(currentContext.contextToken(), requestToken)) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STALE);
        }
    }

    private CharacterSnapshotSlot validateTarget(
            CanonicalTarget canonicalTarget,
            WorkerCharacterFactComparisonCompleteRequest request
    ) {
        CharacterFactOperation operation = request.operation();
        if (operation == CharacterFactOperation.ADD) {
            if (request.targetFactType() != null || !isBlank(request.targetFactKey())) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_TARGET_INVALID);
            }
            return new CharacterSnapshotSlot(canonicalTarget.factType(), canonicalTarget.factKey());
        }
        if (operation == CharacterFactOperation.UPDATE || operation == CharacterFactOperation.MERGE) {
            if (request.targetFactType() != canonicalTarget.factType()
                    || !Objects.equals(normalize(request.targetFactKey()), canonicalTarget.factKey())) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_TARGET_INVALID);
            }
            return new CharacterSnapshotSlot(canonicalTarget.factType(), canonicalTarget.factKey());
        }
        if (request.targetFactType() != null || !isBlank(request.targetFactKey())) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_TARGET_INVALID);
        }
        return null;
    }

    private List<CharacterSnapshotSlot> validateRemovedEntries(
            Map<CharacterSnapshotSlot, CharacterSnapshotEntry> currentSnapshot,
            Set<CharacterSnapshotSlot> contextSlots,
            CharacterSnapshotSlot targetSlot,
            WorkerCharacterFactComparisonCompleteRequest request
    ) {
        if (!request.removedSnapshotEntries().isEmpty()
                && (targetSlot == null || targetSlot.factType() != CharacterFactType.STATUS)) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID);
        }
        Set<CharacterSnapshotSlot> distinct = new HashSet<>();
        for (WorkerCharacterFactComparisonCompleteRequest.SnapshotEntry removed
                : request.removedSnapshotEntries()) {
            CharacterSnapshotSlot slot = new CharacterSnapshotSlot(
                    removed.factType(),
                    normalize(removed.factKey())
            );
            if (!distinct.add(slot)
                    || !currentSnapshot.containsKey(slot)
                    || !contextSlots.contains(slot)
                    || slot.equals(targetSlot)
                    || slot.factType() != CharacterFactType.STATUS) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_TARGET_INVALID);
            }
        }
        return List.copyOf(distinct);
    }

    private void validateOperation(
            CanonicalTarget canonicalTarget,
            Map<CharacterSnapshotSlot, CharacterSnapshotEntry> currentSnapshot,
            CharacterSnapshotSlot targetSlot,
            List<CharacterSnapshotSlot> removedSlots,
            WorkerCharacterFactComparisonCompleteRequest request
    ) {
        CharacterFactOperation operation = request.operation();
        boolean hasProposedValue = request.proposedValueJson() != null;
        boolean proposedFactValueProvided = request.proposedFactValue() != null;
        boolean hasProposedFactValue = !isBlank(request.proposedFactValue());
        validateTemporalScope(operation, request.temporalScope());
        if (!removedSlots.isEmpty()
                && (request.temporalScope() != CharacterFactTemporalScope.PRESENT
                || canonicalTarget.factType() != CharacterFactType.STATUS
                || operation != CharacterFactOperation.ADD
                && operation != CharacterFactOperation.UPDATE
                && operation != CharacterFactOperation.MERGE)) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID);
        }
        if (operation == CharacterFactOperation.ADD) {
            if (currentSnapshot.containsKey(targetSlot)
                    || !hasProposedValue
                    || !hasProposedFactValue) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID);
            }
            return;
        }
        if (operation == CharacterFactOperation.UPDATE || operation == CharacterFactOperation.MERGE) {
            if (!currentSnapshot.containsKey(targetSlot)
                    || !hasProposedValue
                    || !hasProposedFactValue) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID);
            }
            return;
        }
        if (operation == CharacterFactOperation.HISTORY_ONLY) {
            if (!removedSlots.isEmpty() || hasProposedValue || proposedFactValueProvided) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID);
            }
            return;
        }
        if (!removedSlots.isEmpty() || hasProposedValue || proposedFactValueProvided) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID);
        }
    }

    private void validateTemporalScope(
            CharacterFactOperation operation,
            CharacterFactTemporalScope temporalScope
    ) {
        if ((temporalScope == CharacterFactTemporalScope.PAST
                || temporalScope == CharacterFactTemporalScope.HYPOTHETICAL)
                && operation != CharacterFactOperation.HISTORY_ONLY
                && operation != CharacterFactOperation.REVIEW_REQUIRED) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID);
        }
        if (temporalScope == CharacterFactTemporalScope.UNKNOWN
                && operation != CharacterFactOperation.REVIEW_REQUIRED) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID);
        }
    }

    private SettingCandidate getOwnedProcessingCandidate(AnalysisJob analysisJob, UUID candidateId) {
        SettingCandidate candidate = settingCandidateRepository.findByIdAndWorkIdForUpdate(
                        candidateId,
                        analysisJob.getWork().getId()
                )
                .orElseThrow(() -> new AppException(CharacterErrorCode.SETTING_CANDIDATE_NOT_FOUND));
        validateOwnership(analysisJob, candidate);
        if (candidate.getComparisonStatus() != CharacterFactComparisonStatus.PROCESSING) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
        }
        return candidate;
    }

    private SettingCandidate lockLinkedCandidate(AnalysisJob analysisJob) {
        if (analysisJob.getSettingCandidate() == null) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_WORKER_JOB_INVALID);
        }
        SettingCandidate candidate = settingCandidateRepository.findByIdAndWorkIdForUpdate(
                        analysisJob.getSettingCandidate().getId(),
                        analysisJob.getWork().getId()
                )
                .orElseThrow(() -> new AppException(CharacterErrorCode.SETTING_CANDIDATE_NOT_FOUND));
        validateOwnership(analysisJob, candidate);
        return candidate;
    }

    private void validateOwnership(AnalysisJob analysisJob, SettingCandidate candidate) {
        boolean owned = analysisJob.getJobType() == AnalysisJobType.SETTING_EXTRACTION
                ? candidate.getAnalysisJob() != null
                && candidate.getAnalysisJob().getId().equals(analysisJob.getId())
                : analysisJob.getJobType() == AnalysisJobType.CHARACTER_FACT_COMPARISON
                && analysisJob.getSettingCandidate() != null
                && analysisJob.getSettingCandidate().getId().equals(candidate.getId());
        if (!owned) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_WORKER_JOB_INVALID);
        }
    }

    private CanonicalTarget resolveCanonicalTarget(SettingCandidate candidate) {
        List<CharacterSettingSchema> schemas = schemaRepository.findAllActiveForWork(candidate.getWork().getId());
        SettingCandidateSchemaMatch match = schemaResolver.resolve(
                candidate.getAttributeName(),
                candidate.getValueType(),
                schemas
        );
        valueValidator.validateCandidate(
                candidate,
                match.matchedSchema().getFactType(),
                match.matchedSchema().getValueType()
        );
        return new CanonicalTarget(
                match.matchedSchema().getFactType(),
                match.factKey(),
                match.matchedSchema().getValueType()
        );
    }

    private WorkCharacter getMatchedCharacter(SettingCandidate candidate, boolean forUpdate) {
        UUID characterId = candidate.getMatchedCharacterId();
        if (characterId == null) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCH_STATUS_CONFLICT);
        }
        Optional<WorkCharacter> character = forUpdate
                ? workCharacterRepository.findByIdAndWorkIdForUpdate(characterId, candidate.getWork().getId())
                : workCharacterRepository.findByIdAndWorkId(characterId, candidate.getWork().getId());
        return character.filter(value -> value.getStatus() == CharacterStatus.ACTIVE)
                .orElseThrow(() -> new AppException(
                        CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID
                ));
    }

    private String fingerprint(
            SettingCandidate candidate,
            WorkCharacter character,
            CanonicalTarget target,
            List<CharacterSnapshotEntry> entries,
            Map<CharacterSnapshotSlot, List<UUID>> sourceIdsBySlot,
            List<WorkerCharacterFactComparisonContextResponse.PriorCandidate> priorCandidates
    ) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("candidateId", candidate.getId());
        canonical.put("attributeName", candidate.getAttributeName());
        canonical.put("attributeValue", candidate.getAttributeValue());
        canonical.put("valueJson", workerMapper.toJsonValue(candidate.getValueJson()));
        canonical.put("matchedCharacterId", character.getId());
        canonical.put("canonicalFactType", target.factType());
        canonical.put("canonicalFactKey", target.factKey());
        canonical.put("snapshotEntries", entries.stream()
                .map(entry -> toHashEntry(entry, sourceIdsBySlot))
                .toList());
        canonical.put("priorCandidates", priorCandidates);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("캐릭터 설정 비교 문맥 hash를 생성할 수 없습니다.", exception);
        }
    }

    private JsonNode toNullableJsonNode(Object value) {
        return value == null ? null : objectMapper.valueToTree(value);
    }

    private Map<String, Object> toHashEntry(
            CharacterSnapshotEntry entry,
            Map<CharacterSnapshotSlot, List<UUID>> sourceIdsBySlot
    ) {
        Map<String, Object> hashEntry = new LinkedHashMap<>();
        hashEntry.put("factType", entry.slot().factType());
        hashEntry.put("factKey", entry.slot().factKey());
        hashEntry.put("factValue", entry.factValue());
        hashEntry.put("valueJson", workerMapper.toJsonValue(entry.valueJson()));
        hashEntry.put("sourceFactIds", sourceIdsBySlot.getOrDefault(entry.slot(), List.of()));
        return hashEntry;
    }

    private String normalize(String value) {
        if (value == null) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_TARGET_INVALID);
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_TARGET_INVALID);
        }
        return normalized;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record CanonicalTarget(
            CharacterFactType factType,
            String factKey,
            SettingValueType valueType
    ) {
    }

    private record ContextSnapshot(
            List<WorkerCharacterFactComparisonContextResponse.SnapshotEntry> entries,
            List<WorkerCharacterFactComparisonContextResponse.PriorCandidate> priorCandidates,
            String contextToken
    ) {
    }
}
