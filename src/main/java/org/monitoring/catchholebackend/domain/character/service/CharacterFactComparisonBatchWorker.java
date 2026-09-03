package org.monitoring.catchholebackend.domain.character.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobLeaseService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.character.dto.request.WorkerCharacterFactComparisonBatchCompleteRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.WorkerCharacterFactComparisonCompleteRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.WorkerCharacterFactComparisonFailRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.WorkerCharacterFactComparisonBatchContextResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.WorkerCharacterFactComparisonBatchPayload;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFactComparisonBatch;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterFactComparisonWorkerMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterFactComparisonDecisionValidator;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSettingValueValidator;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateChronology;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateSchemaMatch;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateSchemaResolver;
import org.monitoring.catchholebackend.domain.character.repository.CharacterFactComparisonBatchRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSnapshotSourceRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactCanonicalKeyResolution;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonBatchStatus;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactSnapshotOrigin;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 캐릭터 후보 batch claim과 projected snapshot 검증을 담당한다. */
@Component
@RequiredArgsConstructor
public class CharacterFactComparisonBatchWorker {

    private static final int CLAIM_SCAN_SIZE = 100;
    private static final int MAX_CONTEXT_ENTRIES = 30;
    private static final String CHARACTER_REF = "K1";
    private static final Pattern DYNAMIC_STATUS_KEY = Pattern.compile(
            "^status\\.[\\p{L}\\p{N}_-]+$"
    );

    private final AnalysisJobLeaseService analysisJobLeaseService;
    private final SettingCandidateRepository settingCandidateRepository;
    private final CharacterFactComparisonBatchRepository comparisonBatchRepository;
    private final WorkCharacterRepository workCharacterRepository;
    private final CharacterSettingSchemaRepository schemaRepository;
    private final CharacterSnapshotSourceRepository snapshotSourceRepository;
    private final SettingCandidateSchemaResolver schemaResolver;
    private final CharacterSnapshotAccessor snapshotAccessor;
    private final CharacterSettingValueValidator valueValidator;
    private final CharacterFactComparisonDecisionValidator decisionValidator;
    private final CharacterFactComparisonWorkerMapper workerMapper;

    @Value("${analysis.character-fact-comparison.max-batch-candidates:10}")
    private int maxBatchCandidates = 10;

    @Value("${analysis.character-fact-comparison.max-batch-input-characters:30000}")
    private int maxBatchInputCharacters = 30000;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @Transactional
    public Optional<WorkerCharacterFactComparisonBatchPayload> claimNext(
            UUID analysisJobId,
            UUID leaseToken
    ) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        validateJobCanCompare(analysisJob);

        List<CandidateTarget> selected;
        if (analysisJob.getJobType() == AnalysisJobType.CHARACTER_FACT_COMPARISON) {
            SettingCandidate candidate = lockHiddenCandidate(analysisJob);
            if (candidate.getComparisonStatus() != CharacterFactComparisonStatus.PENDING) {
                return Optional.empty();
            }
            Optional<CanonicalTarget> target = findValidTarget(candidate);
            if (target.isEmpty()) {
                candidate.quarantineInvalidComparison();
                return Optional.empty();
            }
            selected = List.of(new CandidateTarget(candidate, target.get()));
        } else {
            selected = selectNextBoundedGroup(analysisJob);
            if (selected.isEmpty()) {
                return Optional.empty();
            }
        }

        SettingCandidate first = selected.getFirst().candidate();
        WorkCharacter character = getMatchedCharacter(first, true);
        CharacterFactType factType = selected.getFirst().target().factType();
        CharacterFactComparisonBatch batch = comparisonBatchRepository.saveAndFlush(
                CharacterFactComparisonBatch.create(
                        analysisJob.getWork(),
                        first.getEpisode(),
                        analysisJob,
                        character,
                        factType,
                        selected.size(),
                        character.getSnapshotVersion()
                )
        );
        for (int index = 0; index < selected.size(); index++) {
            selected.get(index).candidate().startComparison(batch, candidateRef(index));
        }
        settingCandidateRepository.flush();
        return Optional.of(toPayload(batch, character, selected));
    }

    @Transactional
    public WorkerCharacterFactComparisonBatchContextResponse getContext(
            UUID analysisJobId,
            UUID comparisonBatchId,
            UUID leaseToken
    ) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        CharacterFactComparisonBatch batch = getBatchForUpdate(analysisJob, comparisonBatchId);
        requireProcessing(batch);
        List<CandidateTarget> candidates = getProcessingBatchCandidates(batch);
        WorkCharacter character = getBatchCharacter(batch, true);
        BatchContext context = buildContext(batch, character, candidates);
        batch.recordContext(character.getSnapshotVersion(), context.contextToken());
        candidates.forEach(value -> value.candidate().recordComparisonContext(
                character.getSnapshotVersion(),
                context.contextToken()
        ));
        return new WorkerCharacterFactComparisonBatchContextResponse(
                batch.getId(),
                CHARACTER_REF,
                character.getName(),
                batch.getCanonicalFactType(),
                character.getSnapshotVersion(),
                toPayloadCandidates(candidates),
                context.responseEntries(),
                context.contextToken()
        );
    }

    @Transactional
    public void complete(
            UUID analysisJobId,
            UUID comparisonBatchId,
            UUID leaseToken,
            WorkerCharacterFactComparisonBatchCompleteRequest request
    ) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        String completionHash = sha256(canonicalCompletion(request));
        CharacterFactComparisonBatch batch = getBatchForUpdate(analysisJob, comparisonBatchId);
        if (batch.isCompletedWith(completionHash)) {
            return;
        }
        requireProcessing(batch);
        List<CandidateTarget> candidates = getProcessingBatchCandidates(batch);
        WorkCharacter character = getBatchCharacter(batch, true);
        BatchContext context = buildContext(batch, character, candidates);
        if (batch.getContextHash() == null
                || batch.getBaseSnapshotVersion() != character.getSnapshotVersion()
                || !Objects.equals(batch.getContextHash(), request.contextToken())
                || !Objects.equals(context.contextToken(), request.contextToken())) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STALE);
        }

        Map<String, WorkerCharacterFactComparisonBatchCompleteRequest.Decision> decisions = uniqueByRef(
                request.decisions(),
                WorkerCharacterFactComparisonBatchCompleteRequest.Decision::candidateRef
        );
        Map<String, WorkerCharacterFactComparisonBatchCompleteRequest.Failure> failures = uniqueByRef(
                request.failures(),
                WorkerCharacterFactComparisonBatchCompleteRequest.Failure::candidateRef
        );
        validateCoverage(candidates, decisions.keySet(), failures.keySet());

        Projection projection = context.projection().copy();
        List<ValidatedDecision> validated = new ArrayList<>();
        Set<String> failedRefs = failures.keySet();
        for (int index = 0; index < candidates.size(); index++) {
            CandidateTarget candidateTarget = candidates.get(index);
            String ref = candidateTarget.candidate().getCharacterComparisonCandidateRef();
            if (failedRefs.contains(ref)) {
                continue;
            }
            ValidatedDecision decision = validateAndProject(
                    candidateTarget,
                    index,
                    decisions.get(ref),
                    candidates,
                    projection
            );
            validated.add(decision);
        }

        LocalDateTime comparedAt = LocalDateTime.now();
        for (ValidatedDecision decision : validated) {
            WorkerCharacterFactComparisonBatchCompleteRequest.Decision requestDecision = decision.request();
            SettingCandidate candidate = decision.candidateTarget().candidate();
            CharacterSnapshotSlot storedTarget = upsertsSnapshot(requestDecision.operation())
                    ? decision.resolvedSlot()
                    : null;
            candidate.completeComparison(
                    requestDecision.operation(),
                    storedTarget == null ? null : storedTarget.factType(),
                    storedTarget == null ? null : storedTarget.factKey(),
                    requestDecision.proposedFactValue(),
                    decision.proposedValueJson(),
                    toRemovedEntriesJson(decision.removedSlots()),
                    requestDecision.temporalScope(),
                    requestDecision.comparisonReason(),
                    objectMapper.valueToTree(requestDecision.rawComparisonJson()),
                    comparedAt,
                    decision.resolvedSlot().factKey(),
                    objectMapper.valueToTree(decision.dependencyCandidateIds())
            );
        }
        failures.forEach((ref, failure) -> findByRef(candidates, ref).candidate().failComparison(
                failure.failureCode(),
                failure.errorMessage()
        ));
        validated.stream()
                .map(ValidatedDecision::candidateTarget)
                .map(CandidateTarget::candidate)
                .filter(candidate -> candidate.getSuggestedOperation() == CharacterFactOperation.EXCLUDE)
                .forEach(SettingCandidate::dismiss);
        batch.complete(completionHash, objectMapper.valueToTree(request.rawComparisonJson()));
    }

    @Transactional
    public void fail(
            UUID analysisJobId,
            UUID comparisonBatchId,
            UUID leaseToken,
            WorkerCharacterFactComparisonFailRequest request
    ) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                analysisJobId,
                leaseToken
        );
        CharacterFactComparisonBatch batch = getBatchForUpdate(analysisJob, comparisonBatchId);
        AnalysisFailureCode failureCode = AnalysisFailureCode.orUnexpected(request.failureCode());
        String errorMessage = Objects.requireNonNull(request.errorMessage()).trim();
        List<SettingCandidate> candidates = settingCandidateRepository
                .findAllByCharacterComparisonBatchIdForUpdate(batch.getId());
        if (!batch.isProcessing()) {
            if (isSameFailedBatchRequest(batch, candidates, failureCode, errorMessage)) {
                return;
            }
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
        }
        if (candidates.size() != batch.getCandidateCount()
                || candidates.stream().anyMatch(candidate ->
                candidate.getComparisonStatus() != CharacterFactComparisonStatus.PROCESSING)) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
        }
        for (SettingCandidate candidate : candidates) {
            candidate.failComparison(failureCode, errorMessage);
        }
        batch.fail(failureCode, errorMessage);
    }

    @Transactional
    public boolean hasCurrentContext(SettingCandidate candidate) {
        CharacterFactComparisonBatch batch = candidate.getCharacterComparisonBatch();
        if (batch == null || candidate.getComparisonContextHash() == null) {
            return false;
        }
        List<SettingCandidate> batchCandidates = SettingCandidateChronology.sorted(
                settingCandidateRepository.findAllByCharacterComparisonBatchIdForUpdate(batch.getId())
        );
        if (batchCandidates.isEmpty()) {
            return false;
        }
        List<CandidateTarget> targets = resolveBatchTargets(batch, batchCandidates, false);
        WorkCharacter character = getBatchCharacter(batch, true);
        if (hasDestructiveDecision(candidate)
                && batch.getBaseSnapshotVersion() != character.getSnapshotVersion()) {
            return false;
        }
        BatchContext context = buildContext(batch, character, targets);
        return Objects.equals(candidate.getComparisonContextHash(), context.contextToken());
    }

    private List<CandidateTarget> selectNextBoundedGroup(AnalysisJob analysisJob) {
        while (true) {
            List<SettingCandidate> seeds = settingCandidateRepository.findComparisonClaimCandidates(
                    analysisJob.getId(),
                    SettingCandidateReviewStatus.PENDING_REVIEW,
                    CharacterFactComparisonStatus.PENDING,
                    PageRequest.of(0, CLAIM_SCAN_SIZE)
            );
            if (seeds.isEmpty()) {
                return List.of();
            }
            boolean quarantined = false;
            for (SettingCandidate seed : seeds) {
                Optional<CanonicalTarget> target = findValidTarget(seed);
                if (target.isEmpty()) {
                    seed.quarantineInvalidComparison();
                    quarantined = true;
                    continue;
                }
                if (comparisonBatchRepository
                        .existsByAnalysisJobIdAndMatchedCharacterIdAndCanonicalFactTypeAndStatus(
                                analysisJob.getId(),
                                seed.getMatchedCharacterId(),
                                target.get().factType(),
                                CharacterFactComparisonBatchStatus.PROCESSING
                        )) {
                    continue;
                }
                List<SettingCandidate> lockedGroup = settingCandidateRepository
                        .findComparisonGroupCandidatesForUpdate(
                                analysisJob.getId(),
                                seed.getMatchedCharacterId(),
                                SettingCandidateReviewStatus.PENDING_REVIEW,
                                CharacterFactComparisonStatus.PENDING
                        );
                List<CandidateTarget> sameType = resolveBatchTargets(
                        null,
                        SettingCandidateChronology.sorted(lockedGroup),
                        true
                ).stream()
                        .filter(value -> value.target().factType() == target.get().factType())
                        .toList();
                return bounded(sameType);
            }
            if (quarantined) {
                settingCandidateRepository.flush();
                continue;
            }
            return List.of();
        }
    }

    private List<CandidateTarget> resolveBatchTargets(
            CharacterFactComparisonBatch batch,
            List<SettingCandidate> candidates,
            boolean quarantineInvalid
    ) {
        List<CandidateTarget> resolved = new ArrayList<>();
        for (SettingCandidate candidate : candidates) {
            Optional<CanonicalTarget> target = findValidTarget(candidate);
            if (target.isEmpty()) {
                if (quarantineInvalid && candidate.getComparisonStatus() == CharacterFactComparisonStatus.PENDING) {
                    candidate.quarantineInvalidComparison();
                }
                continue;
            }
            if (batch != null
                    && (target.get().factType() != batch.getCanonicalFactType()
                    || !Objects.equals(candidate.getMatchedCharacterId(), batch.getMatchedCharacter().getId()))) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_WORKER_JOB_INVALID);
            }
            resolved.add(new CandidateTarget(candidate, target.get()));
        }
        return resolved;
    }

    private List<CandidateTarget> bounded(List<CandidateTarget> candidates) {
        List<CandidateTarget> selected = new ArrayList<>();
        int characters = 0;
        int candidateLimit = Math.min(20, Math.max(1, maxBatchCandidates));
        for (CandidateTarget candidate : candidates) {
            int next = estimatedCharacters(candidate.candidate());
            if (!selected.isEmpty()
                    && (selected.size() >= candidateLimit
                    || characters + next > Math.max(1, maxBatchInputCharacters))) {
                break;
            }
            selected.add(candidate);
            characters += next;
        }
        return List.copyOf(selected);
    }

    private int estimatedCharacters(SettingCandidate candidate) {
        long total = length(candidate.getAttributeName()) + length(candidate.getAttributeValue());
        total += candidate.getValueJson() == null ? 0 : candidate.getValueJson().toString().length();
        total += candidate.getEvidenceSpans() == null ? 0 : candidate.getEvidenceSpans().toString().length();
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private BatchContext buildContext(
            CharacterFactComparisonBatch batch,
            WorkCharacter character,
            List<CandidateTarget> candidates
    ) {
        Projection projection = loadPersistedProjection(character, batch.getCanonicalFactType());
        applyPriorCompletedDecisions(batch, candidates, projection);
        candidates.forEach(value -> projection.registerDependency(value.candidate().getId()));
        Projection selected = selectContextEntries(projection, candidates);
        Projection validationProjection = projection.copy();
        List<WorkerCharacterFactComparisonBatchContextResponse.SnapshotEntry> responseEntries =
                new ArrayList<>();
        int index = 0;
        for (ProjectionValue value : selected.bySlot().values()) {
            String ref = persistedRef(index++);
            validationProjection.byRef().put(ref, value);
            responseEntries.add(new WorkerCharacterFactComparisonBatchContextResponse.SnapshotEntry(
                    ref,
                    value.origin(),
                    null,
                    List.of(),
                    value.entry().slot().factType(),
                    value.entry().slot().factKey(),
                    value.entry().factValue(),
                    workerMapper.toJsonValue(value.entry().valueJson())
            ));
        }
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("batchId", batch.getId());
        fingerprint.put("characterId", character.getId());
        fingerprint.put("snapshotVersion", character.getSnapshotVersion());
        fingerprint.put("factType", batch.getCanonicalFactType());
        fingerprint.put("candidates", candidates.stream().map(this::candidateHashValue).toList());
        fingerprint.put("snapshotEntries", projection.bySlot().values().stream()
                .map(this::projectionHashValue)
                .toList());
        String contextToken = sha256(fingerprint);
        return new BatchContext(
                List.copyOf(responseEntries),
                validationProjection,
                contextToken
        );
    }

    private Projection loadPersistedProjection(WorkCharacter character, CharacterFactType factType) {
        List<CharacterSnapshotSource> sources =
                snapshotSourceRepository.findAllByWorkCharacterIdOrderByFactTypeAscFactKeyAscSourceOrderAsc(
                        character.getId()
                );
        Map<CharacterSnapshotSlot, List<CharacterFact>> sourceFactsBySlot = new LinkedHashMap<>();
        for (CharacterSnapshotSource source : sources) {
            CharacterSnapshotSlot slot = new CharacterSnapshotSlot(source.getFactType(), source.getFactKey());
            sourceFactsBySlot.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(source.getSourceFact());
        }
        LinkedHashMap<CharacterSnapshotSlot, ProjectionValue> values = new LinkedHashMap<>();
        snapshotAccessor.read(character, sourceFactsBySlot).values().stream()
                .filter(entry -> entry.slot().factType() == factType)
                .sorted(Comparator.comparing(entry -> entry.slot().factKey()))
                .forEach(entry -> values.put(
                        entry.slot(),
                        new ProjectionValue(
                                entry,
                                CharacterFactSnapshotOrigin.PERSISTED,
                                null,
                                List.of()
                        )
                ));
        return new Projection(values, new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    private void applyPriorCompletedDecisions(
            CharacterFactComparisonBatch batch,
            List<CandidateTarget> currentCandidates,
            Projection projection
    ) {
        Set<UUID> currentIds = currentCandidates.stream()
                .map(value -> value.candidate().getId())
                .collect(Collectors.toSet());
        List<SettingCandidate> completed = SettingCandidateChronology.sorted(
                settingCandidateRepository.findCompletedComparisonCandidates(
                        batch.getAnalysisJob().getId(),
                        batch.getMatchedCharacter().getId(),
                        SettingCandidateReviewStatus.PENDING_REVIEW,
                        CharacterFactComparisonStatus.COMPLETED
                )
        );
        List<SettingCandidate> chronology = new ArrayList<>(completed);
        chronology.addAll(currentCandidates.stream().map(CandidateTarget::candidate).toList());
        for (SettingCandidate candidate : SettingCandidateChronology.sorted(chronology)) {
            // Bounded batch는 전체 chronology의 연속 prefix를 처리한다. 현재 batch의 첫 후보를
            // 만난 뒤의 완료 decision은 미래 묶음이므로 이전 context에 역투영하면 안 된다.
            if (currentIds.contains(candidate.getId())) {
                break;
            }
            if (candidate.getSuggestedOperation() == null) {
                continue;
            }
            projection.registerDependency(candidate.getId());
            Optional<CanonicalTarget> target = findValidTarget(candidate);
            if (target.isEmpty() || target.get().factType() != batch.getCanonicalFactType()) {
                continue;
            }
            String resolvedKey = isBlank(candidate.getResolvedCanonicalFactKey())
                    ? target.get().factKey()
                    : candidate.getResolvedCanonicalFactKey().trim();
            CharacterSnapshotSlot slot = new CharacterSnapshotSlot(target.get().factType(), resolvedKey);
            parseStoredRemovedSlots(candidate).forEach(projection.bySlot()::remove);
            if (upsertsSnapshot(candidate.getSuggestedOperation())) {
                List<UUID> dependencies = parseStoredDependencyIds(candidate);
                LinkedHashSet<UUID> withSource = new LinkedHashSet<>(dependencies);
                withSource.add(candidate.getId());
                projection.bySlot().put(
                        slot,
                        new ProjectionValue(
                                snapshotAccessor.entry(
                                        slot.factType(),
                                        slot.factKey(),
                                        candidate.getProposedFactValue(),
                                        candidate.getProposedValueJson()
                                ),
                                CharacterFactSnapshotOrigin.PRIOR_DECISION,
                                candidate.getId(),
                                List.copyOf(withSource)
                        )
                );
            }
        }
    }

    private Projection selectContextEntries(
            Projection projection,
            List<CandidateTarget> candidates
    ) {
        LinkedHashMap<CharacterSnapshotSlot, ProjectionValue> selected = new LinkedHashMap<>();
        Set<CharacterSnapshotSlot> exactSlots = candidates.stream()
                .map(value -> new CharacterSnapshotSlot(
                        value.target().factType(),
                        value.target().factKey()
                ))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        exactSlots.forEach(slot -> {
            ProjectionValue value = projection.bySlot().get(slot);
            if (value != null && selected.size() < MAX_CONTEXT_ENTRIES) {
                selected.put(slot, value);
            }
        });
        projection.bySlot().entrySet().stream()
                .filter(entry -> !selected.containsKey(entry.getKey()))
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(CharacterSnapshotSlot::factKey)))
                .limit(Math.max(0, MAX_CONTEXT_ENTRIES - selected.size()))
                .forEach(entry -> selected.put(entry.getKey(), entry.getValue()));
        return new Projection(
                selected,
                new LinkedHashMap<>(),
                new LinkedHashMap<>(projection.dependencyOrder())
        );
    }

    private ValidatedDecision validateAndProject(
            CandidateTarget candidateTarget,
            int candidateIndex,
            WorkerCharacterFactComparisonBatchCompleteRequest.Decision request,
            List<CandidateTarget> candidates,
            Projection projection
    ) {
        if (request == null) {
            throw invalidBatchResponse();
        }
        SettingCandidate candidate = candidateTarget.candidate();
        CanonicalTarget initial = candidateTarget.target();
        String resolvedKey = validateResolvedCanonicalKey(initial, request.resolvedCanonicalFactKey());
        CharacterSnapshotSlot resolvedSlot = new CharacterSnapshotSlot(initial.factType(), resolvedKey);

        ProjectionValue targetValue = resolveCurrentReference(
                request.targetSnapshotRef(),
                projection,
                request.operation() == CharacterFactOperation.UPDATE
                        || request.operation() == CharacterFactOperation.MERGE
        );
        CharacterSnapshotSlot targetSlot = targetValue == null ? null : targetValue.entry().slot();
        if (targetSlot != null && !targetSlot.equals(resolvedSlot)) {
            throw invalidTarget();
        }

        LinkedHashSet<ProjectionValue> removedValues = new LinkedHashSet<>();
        for (String ref : request.removedSnapshotRefs()) {
            ProjectionValue value = resolveCurrentReference(ref, projection, true);
            if (!removedValues.add(value)) {
                throw invalidTarget();
            }
        }
        List<CharacterSnapshotSlot> removedSlots = removedValues.stream()
                .map(value -> value.entry().slot())
                .toList();
        JsonNode proposedValueJson = toNullableJsonNode(request.proposedValueJson());
        validateBatchOperation(
                candidateTarget,
                request,
                resolvedSlot,
                targetValue,
                removedSlots,
                projection,
                proposedValueJson
        );

        List<UUID> derivedDependencies = deriveDependencies(targetValue, removedValues, projection);
        Set<UUID> expectedCurrentBatchDependencies = derivedDependencies.stream()
                .filter(id -> indexOfCandidate(candidates, id) >= 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<UUID> explicitDependencies = resolveExplicitDependencies(
                request.dependencyCandidateRefs(),
                candidates,
                candidateIndex
        );
        if (!explicitDependencies.equals(expectedCurrentBatchDependencies)) {
            throw invalidBatchResponse();
        }

        removedSlots.forEach(projection.bySlot()::remove);
        if (upsertsSnapshot(request.operation())) {
            LinkedHashSet<UUID> outputDependencies = new LinkedHashSet<>(derivedDependencies);
            outputDependencies.add(candidate.getId());
            ProjectionValue projected = new ProjectionValue(
                    snapshotAccessor.entry(
                            resolvedSlot.factType(),
                            resolvedSlot.factKey(),
                            request.proposedFactValue().trim(),
                            proposedValueJson
                    ),
                    CharacterFactSnapshotOrigin.PRIOR_DECISION,
                    candidate.getId(),
                    List.copyOf(outputDependencies)
            );
            projection.bySlot().put(resolvedSlot, projected);
            projection.byRef().put(projectedRef(candidateIndex), projected);
        }
        return new ValidatedDecision(
                candidateTarget,
                request,
                resolvedSlot,
                targetSlot,
                removedSlots,
                proposedValueJson,
                derivedDependencies
        );
    }

    private void validateBatchOperation(
            CandidateTarget candidateTarget,
            WorkerCharacterFactComparisonBatchCompleteRequest.Decision request,
            CharacterSnapshotSlot resolvedSlot,
            ProjectionValue targetValue,
            List<CharacterSnapshotSlot> removedSlots,
            Projection projection,
            JsonNode proposedValueJson
    ) {
        decisionValidator.validate(new CharacterFactComparisonDecisionValidator.Decision(
                request.operation(),
                request.temporalScope(),
                candidateTarget.target().factType(),
                candidateTarget.target().valueType(),
                resolvedSlot,
                !isBlank(request.targetSnapshotRef()),
                targetValue != null,
                projection.bySlot().containsKey(resolvedSlot),
                false,
                removedSlots,
                request.proposedFactValue(),
                proposedValueJson,
                candidateTarget.candidate().getValueJson()
        ));
    }

    private ProjectionValue resolveCurrentReference(
            String reference,
            Projection projection,
            boolean required
    ) {
        if (isBlank(reference)) {
            if (required) {
                throw invalidTarget();
            }
            return null;
        }
        ProjectionValue value = projection.byRef().get(reference.trim());
        if (value == null || projection.bySlot().get(value.entry().slot()) != value) {
            throw invalidTarget();
        }
        return value;
    }

    private List<UUID> deriveDependencies(
            ProjectionValue target,
            Set<ProjectionValue> removed,
            Projection projection
    ) {
        LinkedHashSet<UUID> dependencies = new LinkedHashSet<>();
        if (target != null) {
            dependencies.addAll(target.dependencyCandidateIds());
        }
        removed.forEach(value -> dependencies.addAll(value.dependencyCandidateIds()));
        return dependencies.stream()
                .sorted(Comparator
                        .comparingInt((UUID id) -> projection.dependencyOrder()
                                .getOrDefault(id, Integer.MAX_VALUE))
                        .thenComparing(UUID::compareTo))
                .toList();
    }

    private Set<UUID> resolveExplicitDependencies(
            List<String> refs,
            List<CandidateTarget> candidates,
            int currentIndex
    ) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (String ref : refs) {
            int index = parseNumberedRef(ref, 'C') - 1;
            if (index < 0 || index >= currentIndex || index >= candidates.size()) {
                throw invalidBatchResponse();
            }
            if (!ids.add(candidates.get(index).candidate().getId())) {
                throw invalidBatchResponse();
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    private String validateResolvedCanonicalKey(CanonicalTarget initial, String requested) {
        String normalized = normalize(requested);
        boolean mutable = initial.factType() == CharacterFactType.STATUS
                && initial.resolution() == CharacterFactCanonicalKeyResolution.PATTERN;
        if (!mutable && !normalized.equals(initial.factKey())) {
            throw invalidTarget();
        }
        if (mutable && (normalized.length() > 150 || !DYNAMIC_STATUS_KEY.matcher(normalized).matches())) {
            throw invalidTarget();
        }
        return normalized;
    }

    private Map<String, Object> canonicalCompletion(
            WorkerCharacterFactComparisonBatchCompleteRequest request
    ) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("contextToken", request.contextToken());
        canonical.put("decisions", request.decisions().stream()
                .sorted(Comparator.comparing(WorkerCharacterFactComparisonBatchCompleteRequest.Decision::candidateRef))
                .toList());
        canonical.put("failures", request.failures().stream()
                .sorted(Comparator.comparing(WorkerCharacterFactComparisonBatchCompleteRequest.Failure::candidateRef))
                .toList());
        canonical.put("rawComparisonJson", request.rawComparisonJson());
        return canonical;
    }

    private <T> Map<String, T> uniqueByRef(List<T> values, Function<T, String> refExtractor) {
        Map<String, T> result = new LinkedHashMap<>();
        for (T value : values) {
            String ref = refExtractor.apply(value);
            if (result.putIfAbsent(ref, value) != null) {
                throw invalidBatchResponse();
            }
        }
        return result;
    }

    private void validateCoverage(
            List<CandidateTarget> candidates,
            Set<String> decisionRefs,
            Set<String> failureRefs
    ) {
        Set<String> expected = candidates.stream()
                .map(value -> value.candidate().getCharacterComparisonCandidateRef())
                .collect(Collectors.toSet());
        Set<String> actual = new LinkedHashSet<>(decisionRefs);
        boolean disjoint = actual.stream().noneMatch(failureRefs::contains);
        actual.addAll(failureRefs);
        if (!disjoint || !actual.equals(expected)) {
            throw invalidBatchResponse();
        }
    }

    private List<CandidateTarget> getProcessingBatchCandidates(CharacterFactComparisonBatch batch) {
        List<SettingCandidate> candidates = SettingCandidateChronology.sorted(
                settingCandidateRepository.findAllByCharacterComparisonBatchIdForUpdate(batch.getId())
        );
        if (candidates.size() != batch.getCandidateCount()
                || candidates.stream().anyMatch(candidate ->
                candidate.getComparisonStatus() != CharacterFactComparisonStatus.PROCESSING)) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
        }
        List<CandidateTarget> targets = resolveBatchTargets(batch, candidates, false);
        if (targets.size() != candidates.size()) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_WORKER_JOB_INVALID);
        }
        for (int index = 0; index < targets.size(); index++) {
            if (!candidateRef(index).equals(
                    targets.get(index).candidate().getCharacterComparisonCandidateRef()
            )) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_WORKER_JOB_INVALID);
            }
        }
        return targets;
    }

    private CandidateTarget findByRef(List<CandidateTarget> candidates, String ref) {
        return candidates.stream()
                .filter(value -> Objects.equals(
                        value.candidate().getCharacterComparisonCandidateRef(),
                        ref
                ))
                .findFirst()
                .orElseThrow(this::invalidBatchResponse);
    }

    private WorkerCharacterFactComparisonBatchPayload toPayload(
            CharacterFactComparisonBatch batch,
            WorkCharacter character,
            List<CandidateTarget> candidates
    ) {
        return new WorkerCharacterFactComparisonBatchPayload(
                batch.getId(),
                batch.getWork().getId(),
                batch.getSourceEpisode() == null ? null : batch.getSourceEpisode().getId(),
                CHARACTER_REF,
                character.getName(),
                batch.getCanonicalFactType(),
                toPayloadCandidates(candidates)
        );
    }

    private List<WorkerCharacterFactComparisonBatchPayload.Candidate> toPayloadCandidates(
            List<CandidateTarget> candidates
    ) {
        List<WorkerCharacterFactComparisonBatchPayload.Candidate> result = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            CandidateTarget candidateTarget = candidates.get(index);
            SettingCandidate candidate = candidateTarget.candidate();
            result.add(new WorkerCharacterFactComparisonBatchPayload.Candidate(
                    candidate.getCharacterComparisonCandidateRef(),
                    projectedRef(index),
                    candidate.getEpisode() == null ? null : candidate.getEpisode().getEpisodeNo(),
                    candidate.getAttributeName(),
                    candidateTarget.target().factKey(),
                    candidateTarget.target().resolution(),
                    candidate.getAttributeValue(),
                    candidate.getValueType(),
                    workerMapper.toJsonValue(candidate.getValueJson()),
                    workerMapper.toEvidenceSpans(candidate.getEvidenceSpans()),
                    candidate.getConfidence()
            ));
        }
        return List.copyOf(result);
    }

    private CanonicalTarget resolveTarget(SettingCandidate candidate) {
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
                match.matchedSchema().getValueType(),
                match.canonicalKeyResolution()
        );
    }

    private Optional<CanonicalTarget> findValidTarget(SettingCandidate candidate) {
        try {
            return Optional.of(resolveTarget(candidate));
        } catch (AppException exception) {
            if (exception.getResultCode() instanceof CharacterErrorCode) {
                return Optional.empty();
            }
            throw exception;
        }
    }

    private CharacterFactComparisonBatch getBatchForUpdate(
            AnalysisJob analysisJob,
            UUID comparisonBatchId
    ) {
        return comparisonBatchRepository.findByIdAndAnalysisJobIdForUpdate(
                        comparisonBatchId,
                        analysisJob.getId()
                )
                .filter(batch -> batch.getWork().getId().equals(analysisJob.getWork().getId()))
                .orElseThrow(() -> new AppException(
                        CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_BATCH_NOT_FOUND
                ));
    }

    private WorkCharacter getBatchCharacter(CharacterFactComparisonBatch batch, boolean forUpdate) {
        UUID characterId = batch.getMatchedCharacter().getId();
        Optional<WorkCharacter> character = forUpdate
                ? workCharacterRepository.findByIdAndWorkIdForUpdate(characterId, batch.getWork().getId())
                : workCharacterRepository.findByIdAndWorkId(characterId, batch.getWork().getId());
        return character.filter(value -> value.getStatus() == CharacterStatus.ACTIVE)
                .orElseThrow(() -> new AppException(
                        CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID
                ));
    }

    private WorkCharacter getMatchedCharacter(SettingCandidate candidate, boolean forUpdate) {
        if (candidate.getMatchedCharacterId() == null) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCH_STATUS_CONFLICT);
        }
        Optional<WorkCharacter> character = forUpdate
                ? workCharacterRepository.findByIdAndWorkIdForUpdate(
                        candidate.getMatchedCharacterId(),
                        candidate.getWork().getId()
                )
                : workCharacterRepository.findByIdAndWorkId(
                        candidate.getMatchedCharacterId(),
                        candidate.getWork().getId()
                );
        return character.filter(value -> value.getStatus() == CharacterStatus.ACTIVE)
                .orElseThrow(() -> new AppException(
                        CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID
                ));
    }

    private SettingCandidate lockHiddenCandidate(AnalysisJob analysisJob) {
        if (analysisJob.getSettingCandidate() == null) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_WORKER_JOB_INVALID);
        }
        SettingCandidate candidate = settingCandidateRepository.findByIdAndWorkIdForUpdate(
                        analysisJob.getSettingCandidate().getId(),
                        analysisJob.getWork().getId()
                )
                .orElseThrow(() -> new AppException(CharacterErrorCode.SETTING_CANDIDATE_NOT_FOUND));
        if (candidate.getAnalysisJob() == null
                || analysisJob.getJobType() != AnalysisJobType.CHARACTER_FACT_COMPARISON) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_WORKER_JOB_INVALID);
        }
        return candidate;
    }

    private void validateJobCanCompare(AnalysisJob analysisJob) {
        if (analysisJob.getJobType() == AnalysisJobType.SETTING_EXTRACTION) {
            if (!analysisJob.hasReachedCheckpoint(AnalysisJobCheckpointStage.CHARACTER_CANDIDATES_SAVED)) {
                throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_CHECKPOINT_INCOMPLETE);
            }
            return;
        }
        if (analysisJob.getJobType() != AnalysisJobType.CHARACTER_FACT_COMPARISON) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_WORKER_JOB_INVALID);
        }
    }

    private void requireProcessing(CharacterFactComparisonBatch batch) {
        if (!batch.isProcessing()) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
        }
    }

    private boolean isSameFailedBatchRequest(
            CharacterFactComparisonBatch batch,
            List<SettingCandidate> candidates,
            AnalysisFailureCode failureCode,
            String errorMessage
    ) {
        return batch.getStatus() == CharacterFactComparisonBatchStatus.FAILED
                && batch.getFailureCode() == failureCode
                && Objects.equals(batch.getErrorMessage(), errorMessage)
                && candidates.size() == batch.getCandidateCount()
                && candidates.stream().allMatch(candidate ->
                candidate.getComparisonStatus() == CharacterFactComparisonStatus.FAILED
                        && candidate.getComparisonFailureCode() == failureCode
                        && Objects.equals(candidate.getComparisonErrorMessage(), errorMessage));
    }

    private List<CharacterSnapshotSlot> parseStoredRemovedSlots(SettingCandidate candidate) {
        JsonNode values = candidate.getRemovedSnapshotEntriesJson();
        if (values == null || !values.isArray()) {
            return List.of();
        }
        List<CharacterSnapshotSlot> slots = new ArrayList<>();
        for (JsonNode value : values) {
            try {
                slots.add(new CharacterSnapshotSlot(
                        CharacterFactType.valueOf(value.path("factType").asText()),
                        normalize(value.path("factKey").asText())
                ));
            } catch (IllegalArgumentException exception) {
                throw invalidTarget();
            }
        }
        return List.copyOf(slots);
    }

    private List<UUID> parseStoredDependencyIds(SettingCandidate candidate) {
        JsonNode values = candidate.getComparisonDependencyCandidateIds();
        if (values == null || !values.isArray()) {
            return List.of();
        }
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (JsonNode value : values) {
            try {
                ids.add(UUID.fromString(value.asText()));
            } catch (IllegalArgumentException exception) {
                throw invalidBatchResponse();
            }
        }
        return List.copyOf(ids);
    }

    private JsonNode toRemovedEntriesJson(List<CharacterSnapshotSlot> slots) {
        return objectMapper.valueToTree(slots.stream()
                .map(slot -> new WorkerCharacterFactComparisonCompleteRequest.SnapshotEntry(
                        slot.factType(),
                        slot.factKey()
                ))
                .toList());
    }

    private boolean hasDestructiveDecision(SettingCandidate candidate) {
        JsonNode removals = candidate.getRemovedSnapshotEntriesJson();
        return candidate.getSuggestedOperation() == CharacterFactOperation.REMOVE
                || removals != null && removals.isArray() && !removals.isEmpty();
    }

    private boolean upsertsSnapshot(CharacterFactOperation operation) {
        return operation == CharacterFactOperation.ADD
                || operation == CharacterFactOperation.UPDATE
                || operation == CharacterFactOperation.MERGE;
    }

    private Map<String, Object> candidateHashValue(CandidateTarget value) {
        SettingCandidate candidate = value.candidate();
        Map<String, Object> hash = new LinkedHashMap<>();
        hash.put("candidateId", candidate.getId());
        hash.put("candidateRef", candidate.getCharacterComparisonCandidateRef());
        hash.put("episodeNo", candidate.getEpisode() == null ? null : candidate.getEpisode().getEpisodeNo());
        hash.put("attributeName", candidate.getAttributeName());
        hash.put("attributeValue", candidate.getAttributeValue());
        hash.put("valueJson", workerMapper.toJsonValue(candidate.getValueJson()));
        hash.put("evidenceSpans", workerMapper.toJsonValue(candidate.getEvidenceSpans()));
        hash.put("initialCanonicalFactKey", value.target().factKey());
        hash.put("canonicalKeyResolution", value.target().resolution());
        return hash;
    }

    private Map<String, Object> projectionHashValue(ProjectionValue value) {
        Map<String, Object> hash = new LinkedHashMap<>();
        hash.put("factType", value.entry().slot().factType());
        hash.put("factKey", value.entry().slot().factKey());
        hash.put("factValue", value.entry().factValue());
        hash.put("valueJson", workerMapper.toJsonValue(value.entry().valueJson()));
        hash.put("origin", value.origin());
        hash.put("sourceCandidateId", value.sourceCandidateId());
        hash.put("dependencyCandidateIds", value.dependencyCandidateIds());
        return hash;
    }

    private JsonNode toNullableJsonNode(Object value) {
        return value == null ? null : objectMapper.valueToTree(value);
    }

    private String sha256(Object value) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("캐릭터 설정 비교 묶음 hash를 생성할 수 없습니다.", exception);
        }
    }

    private int indexOfCandidate(List<CandidateTarget> candidates, UUID candidateId) {
        for (int index = 0; index < candidates.size(); index++) {
            if (candidates.get(index).candidate().getId().equals(candidateId)) {
                return index;
            }
        }
        return -1;
    }

    private int parseNumberedRef(String ref, char prefix) {
        if (ref == null || ref.length() < 2 || ref.charAt(0) != prefix) {
            throw invalidBatchResponse();
        }
        try {
            return Integer.parseInt(ref.substring(1));
        } catch (NumberFormatException exception) {
            throw invalidBatchResponse();
        }
    }

    private String normalize(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw invalidTarget();
        }
        return value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    private String candidateRef(int index) {
        return "C" + (index + 1);
    }

    private String projectedRef(int index) {
        return "Q" + (index + 1);
    }

    private String persistedRef(int index) {
        return "P" + (index + 1);
    }

    private AppException invalidTarget() {
        return new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_TARGET_INVALID);
    }

    private AppException invalidBatchResponse() {
        return new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_BATCH_RESPONSE_INVALID);
    }

    private record CanonicalTarget(
            CharacterFactType factType,
            String factKey,
            SettingValueType valueType,
            CharacterFactCanonicalKeyResolution resolution
    ) {
    }

    private record CandidateTarget(SettingCandidate candidate, CanonicalTarget target) {
    }

    private record ProjectionValue(
            CharacterSnapshotEntry entry,
            CharacterFactSnapshotOrigin origin,
            UUID sourceCandidateId,
            List<UUID> dependencyCandidateIds
    ) {
    }

    private record Projection(
            LinkedHashMap<CharacterSnapshotSlot, ProjectionValue> bySlot,
            LinkedHashMap<String, ProjectionValue> byRef,
            LinkedHashMap<UUID, Integer> dependencyOrder
    ) {
        private Projection copy() {
            return new Projection(
                    new LinkedHashMap<>(bySlot),
                    new LinkedHashMap<>(byRef),
                    new LinkedHashMap<>(dependencyOrder)
            );
        }

        private void registerDependency(UUID candidateId) {
            dependencyOrder.computeIfAbsent(candidateId, ignored -> dependencyOrder.size());
        }
    }

    private record BatchContext(
            List<WorkerCharacterFactComparisonBatchContextResponse.SnapshotEntry> responseEntries,
            Projection projection,
            String contextToken
    ) {
    }

    private record ValidatedDecision(
            CandidateTarget candidateTarget,
            WorkerCharacterFactComparisonBatchCompleteRequest.Decision request,
            CharacterSnapshotSlot resolvedSlot,
            CharacterSnapshotSlot targetSlot,
            List<CharacterSnapshotSlot> removedSlots,
            JsonNode proposedValueJson,
            List<UUID> dependencyCandidateIds
    ) {
    }
}
