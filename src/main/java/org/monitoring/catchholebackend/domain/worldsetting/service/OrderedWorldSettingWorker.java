package org.monitoring.catchholebackend.domain.worldsetting.service;

import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisFailureDiagnosticsSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisRunContextMapper;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisHumanRejectionPolicy;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingComparisonDiagnostics;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchCompleteRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchContextRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.*;
import org.monitoring.catchholebackend.domain.worldsetting.entity.*;
import org.monitoring.catchholebackend.domain.worldsetting.exception.WorldSettingErrorCode;
import org.monitoring.catchholebackend.domain.worldsetting.exception.OrderedWorldSettingComparisonClaimException;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingWorkerMapper;
import org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingNameNormalizer;
import org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingPropertyView;
import org.monitoring.catchholebackend.domain.worldsetting.repository.*;
import org.monitoring.catchholebackend.domain.worldsetting.type.*;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** 고정 실행 상태를 사용하는 세계관 경로. 일반 회차의 DB 비교 경로와 분리한다. */
@Component
@RequiredArgsConstructor
public class OrderedWorldSettingWorker implements org.monitoring.catchholebackend.domain.analysis.service.AnalysisJournalContributor {

    private final AnalysisFailureDiagnosticsSanitizer failureDiagnosticsSanitizer;
    private final AnalysisRunStateService stateService;
    private final AnalysisStateJournal journal;
    private final AnalysisRunContextMapper contextMapper;
    private final WorldSettingCandidateRepository candidates;
    private final WorldSettingComparisonBatchRepository batches;
    private final WorldSettingComparisonDecisionRepository decisions;
    private final WorldSettingComparisonDecisionSourceRepository sources;
    private final WorldSettingRepository settings;
    private final WorldSettingWorkerMapper mapper;
    private final WorldSettingAnalysisStateMapper stateMapper;
    private final AnalysisHumanRejectionPolicy rejectionPolicy;

    @Override
    public String domain() {
        return "worldSettings";
    }

    @Override
    public void finalizeChanges(AnalysisJob job) {
        if (!job.isOrderedProvisional() || !job.isAutomaticReview()) {
            return;
        }
        Set<String> recordedEvents = new HashSet<>();
        job.getStateJournal().path("changes").forEach(change -> recordedEvents.add(change.path("eventId").asText()));
        List<AnalysisStateChange> references = new ArrayList<>();
        for (WorldSettingCandidate candidate : candidates.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(job.getId())) {
            String eventId = "world-comparison-failed:" + candidate.getId();
            if (recordedEvents.contains(eventId)
                    || !candidate.canDeferFailedComparison()) {
                continue;
            }
            references.add(failedComparisonReference(candidate));
        }
        stateService.appendValidatedChanges(job, job.getInputStateHash(), references);
    }

    private AnalysisStateChange failedComparisonReference(WorldSettingCandidate candidate) {
        String eventId = "world-comparison-failed:" + candidate.getId();
        ObjectNode reference = JsonNodeFactory.instance.objectNode();
        reference.put("domain", domain());
        reference.put("operation", "REVIEW_REQUIRED");
        reference.put("confirmationStatus", "UNCONFIRMED");
        reference.put("reason", candidate.getAutomaticReviewHoldReason() == null
                ? "설정 비교를 완료하지 못해 직접 확인이 필요한 참고 정보입니다."
                : candidate.getAutomaticReviewHoldReason().getMessage());
        reference.put("candidateId", candidate.getId().toString());
        reference.put("category", candidate.getCategory().name());
        reference.put("subjectName", candidate.getSubjectName());
        reference.put("scopeName", candidate.getScopeName());
        reference.put("settingName", candidate.getSettingName());
        reference.put("value", candidate.getExtractedValue());
        reference.put("sourceEpisodeNo", (candidate.getSourceEpisode() == null ? null : candidate.getSourceEpisode().getEpisodeNo()));
        reference.putArray("sourceCandidateIds").add(candidate.getId().toString());
        if (candidate.getEvidenceSpans() != null) {
            reference.set("evidenceSpans", candidate.getEvidenceSpans().deepCopy());
        }
        return new AnalysisStateChange(eventId, List.of("references", eventId), reference, false,
                "REVIEW_REQUIRED", List.of(candidate.getId()));
    }

    public WorkerWorldSettingSubjectPageResponse getSubjects(AnalysisJob job, WorldSettingCategory category,
            int page, int size) {
        List<JsonNode> targets = new ArrayList<>();
        stateService.getProjectedState(job).path("worldSettings").forEach(target -> {
            if (category.name().equals(target.path("category").asText())) {
                targets.add(target);
            }
        });
        targets.sort(Comparator.comparing(target -> target.path("subjectName").asText()));
        int from = Math.min(Math.multiplyExact(page, size), targets.size());
        int to = Math.min(from + size, targets.size());
        return new WorkerWorldSettingSubjectPageResponse(targets.subList(from, to).stream()
                .map(stateMapper::toSubject).toList(), page, to < targets.size());
    }

    public WorkerWorldSettingSubjectResolutionPendingResponse getPendingSubjects(AnalysisJob job) {
        return getPendingSubjects(job, false);
    }

    public WorkerWorldSettingSubjectResolutionPendingResponse getPendingSubjects(AnalysisJob job, boolean includeReferenceSelection
    ) {
        return new WorkerWorldSettingSubjectResolutionPendingResponse(pendingCandidates(job).stream()
                .filter(candidate -> !candidate.hasSubjectResolution())
                .map(candidate -> new WorkerWorldSettingSubjectResolutionPendingResponse.Candidate(
                        candidate.getId(), (candidate.getSourceEpisode() == null ? null : candidate.getSourceEpisode().getId()), candidate.getCategory(),
                        candidate.getSubjectName(), mapper.toResponse(candidate).evidenceSpans(),
                        (candidate.getSourceEpisode() == null ? null : candidate.getSourceEpisode().getEpisodeNo()), org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisSourceResponse.from(candidate.getAnalysisJob()), mapper.toRawExtraction(candidate.getRawExtractionJson()))).toList(),
                contextMapper.toResponse(job, includeReferenceSelection));
    }

    public WorkerWorldSettingSubjectResolutionResponse resolveSubjects(AnalysisJob job,
            WorkerWorldSettingSubjectResolutionRequest request) {
        Map<UUID, WorldSettingCandidate> pending = new LinkedHashMap<>();
        pendingCandidates(job).stream().filter(candidate -> !candidate.hasSubjectResolution())
                .forEach(candidate -> pending.put(candidate.getId(), candidate));
        var prepared = stateService.prepareState(job);
        Set<UUID> seen = new HashSet<>();
        List<WorkerWorldSettingSubjectResolutionResponse.ResolvedSubject> result = new ArrayList<>();
        for (var input : request.resolutions()) {
            WorldSettingCandidate candidate = pending.get(input.candidateId());
            if (candidate == null || !seen.add(candidate.getId())) {
                throw invalid();
            }
            if (input.failureCode() != null) {
                if (!job.isAutomaticReview() || !input.failureCode().isCandidateComparisonFailure()
                        || input.ambiguous() || !input.targetWorldSettingIds().isEmpty()
                        || !input.provisionalSubjectKeys().isEmpty()) {
                    throw invalid();
                }
                candidate.deferSubjectResolution(input.failureCode());
                result.add(new WorkerWorldSettingSubjectResolutionResponse.ResolvedSubject(candidate.getId(),
                        WorldSettingSubjectResolutionType.FAILED, candidate.getCanonicalSubjectKey(),
                        candidate.getSubjectName(), List.of(), List.of()));
                continue;
            }
            LinkedHashSet<String> refs = new LinkedHashSet<>();
            input.targetWorldSettingIds().forEach(id -> refs.add(WorldSettingAnalysisStateMapper.persistedRef(id)));
            refs.addAll(input.provisionalSubjectKeys());
            if (refs.size() != input.targetWorldSettingIds().size() + input.provisionalSubjectKeys().size()
                    || refs.size() > 20) {
                throw invalid();
            }
            if (input.ambiguous()) {
                refs.clear();
            }
            if (refs.isEmpty() && !input.ambiguous()) {
                refs.add(WorldSettingAnalysisStateMapper.provisionalRef(candidate.getId()));
            }
            JsonNode projected = prepared.projectedState().path("worldSettings");
            for (String ref : refs) {
                if (!projected.has(ref)) {
                    registerIdentity(prepared, candidate.getCategory(), ref, pending);
                    projected = prepared.projectedState().path("worldSettings");
                }
                requireCategory(projected.path(ref), candidate.getCategory());
            }
            boolean ambiguous = input.ambiguous() || refs.size() != 1;
            String canonicalKey = ambiguous ? "ambiguous:" + candidate.getId() : refs.iterator().next();
            String canonicalName = ambiguous ? candidate.getSubjectName()
                    : projected.path(canonicalKey).path("subjectName").asText();
            List<String> provisionalRefs = refs.stream().filter(ref -> ref.startsWith("provisional-world:")).toList();
            List<UUID> actualIds = refs.stream().filter(ref -> ref.startsWith("world:"))
                    .map(ref -> UUID.fromString(ref.substring("world:".length()))).toList();
            WorldSettingSubjectResolutionType type = ambiguous ? WorldSettingSubjectResolutionType.AMBIGUOUS
                    : projected.path(canonicalKey).path("propertiesJson").isEmpty()
                    ? WorldSettingSubjectResolutionType.NEW : WorldSettingSubjectResolutionType.EXISTING;
            candidate.resolveOrderedSubject(type, canonicalKey, canonicalName,
                    mapper.toJsonNode(actualIds), mapper.toJsonNode(provisionalRefs));
            result.add(new WorkerWorldSettingSubjectResolutionResponse.ResolvedSubject(candidate.getId(), type,
                    canonicalKey, canonicalName, actualIds, provisionalRefs));
        }
        if (!seen.equals(pending.keySet())) {
            throw invalid();
        }
        return new WorkerWorldSettingSubjectResolutionResponse(List.copyOf(result));
    }

    public Optional<WorkerWorldSettingComparisonBatchPayload> claimBatch(AnalysisJob job) {
        return claimBatch(job, false);
    }

    public Optional<WorkerWorldSettingComparisonBatchPayload> claimBatch(AnalysisJob job, boolean includeReferenceSelection
    ) {
        if (candidates.findAllByAnalysisJobIdAndComparisonStatus(job.getId(), WorldSettingComparisonStatus.FAILED)
                .stream().anyMatch(candidate -> !job.isAutomaticReview() || !candidate.canDeferFailedComparison())) {
            throw new OrderedWorldSettingComparisonClaimException();
        }
        var prepared = stateService.prepareState(job);
        var matching = rejectionPolicy.beginMatching();
        for (WorldSettingCandidate candidate : pendingCandidates(job)) {
            if (candidate.hasSubjectResolution()) {
                matching.match(candidate, prepared.projectedState()).ifPresent(reference -> {
                    candidate.preservePriorHumanRejection(reference);
                    prepared.append(
                            List.of(rejectionPolicy.referenceChange(candidate.getId(), reference)));
                });
            }
        }
        while (true) {
            WorldSettingCandidate first = candidates.findComparisonClaimCandidates(job.getId(),
                    WorldSettingReviewStatus.PENDING_REVIEW, WorldSettingComparisonStatus.PENDING,
                    PageRequest.of(0, 1)).stream().findFirst().orElse(null);
            if (first == null) {
                return Optional.empty();
            }
            if (!first.hasSubjectResolution()) {
                throw invalid();
            }
            List<WorldSettingCandidate> group = candidates.findComparisonBatchCandidatesForUpdate(job.getId(),
                    (first.getSourceEpisode() == null ? null : first.getSourceEpisode().getId()), first.getCategory(), first.getCanonicalSubjectKey(),
                    WorldSettingReviewStatus.PENDING_REVIEW, WorldSettingComparisonStatus.PENDING).stream()
                    .filter(candidate -> sameName(first.getScopeName(), candidate.getScopeName()))
                    .sorted(org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingCandidateChronology.comparator())
                    .toList();
            if (job.isAutomaticReview() && first.getSubjectResolutionType() != WorldSettingSubjectResolutionType.AMBIGUOUS) {
                group = group.stream().limit(20).toList();
            }
            WorldSettingComparisonBatch batch = batches.saveAndFlush(WorldSettingComparisonBatch.createOrdered(
                    job.getWork(), first.getSourceEpisode(), job, first.getCategory(), first.getScopeName(),
                    first.getSubjectResolutionType(), first.getCanonicalSubjectKey(), first.getCanonicalSubjectName(),
                    first.getResolvedTargetWorldSettingIds(), first.getResolvedProvisionalSubjectKeys(), group.size()));
            for (int index = 0; index < group.size(); index++) {
                group.get(index).startComparison(batch, "C" + (index + 1));
            }
            if (job.isAutomaticReview() && first.getSubjectResolutionType() == WorldSettingSubjectResolutionType.AMBIGUOUS) {
                holdReviewBatch(job, batch, group, WorldSettingComparisonReviewReason.SUBJECT_UNRESOLVED);
                continue;
            }
            if (group.size() > 20) {
                String message = "누적 비교 입력 상한을 초과했습니다.";
                for (WorldSettingCandidate candidate : group) {
                    candidate.failComparison(org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode.COMPARISON_VALIDATION_FAILED,
                            message);
                }
                batch.fail(org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode.COMPARISON_VALIDATION_FAILED,
                        message);
                throw new OrderedWorldSettingComparisonClaimException();
            }
            if (first.getSubjectResolutionType() == WorldSettingSubjectResolutionType.AMBIGUOUS) {
                holdReviewBatch(job, batch, group, WorldSettingComparisonReviewReason.SUBJECT_UNRESOLVED);
                continue;
            }
            return Optional.of(new WorkerWorldSettingComparisonBatchPayload(batch.getId(), job.getWork().getId(),
                    (batch.getSourceEpisode() == null ? null : batch.getSourceEpisode().getId()), batch.getCategory(), batch.getSubjectResolutionType(),
                    batch.getCanonicalSubjectKey(), batch.getCanonicalSubjectName(),
                    mapper.toUuidList(batch.getResolvedTargetWorldSettingIds()), batch.getRawScopeName(),
                    mapper.toComparisonBatchCandidates(group), stringList(batch.getResolvedProvisionalSubjectKeys()),
                    contextMapper.toResponse(job, includeReferenceSelection), org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisSourceResponse.from(batch.getAnalysisJob())));
        }
    }

    public WorkerWorldSettingComparisonBatchContextResponse getContext(AnalysisJob job, UUID batchId,
            WorkerWorldSettingComparisonBatchContextRequest request) {
        return getContext(job, batchId, request, false);
    }

    public WorkerWorldSettingComparisonBatchContextResponse getContext(AnalysisJob job, UUID batchId,
            WorkerWorldSettingComparisonBatchContextRequest request, boolean includeReferenceSelection
    ) {
        WorldSettingComparisonBatch batch = getBatch(job, batchId);
        requireProcessing(batch);
        List<WorldSettingCandidate> group = processingCandidates(batch);
        Set<String> requested = targetRefs(request.targetWorldSettingIds(), request.provisionalSubjectKeys());
        if (!requested.equals(batchTargetRefs(batch))) {
            throw invalid();
        }
        ObjectNode context = contextState(job, batch, requested);
        String token = journal.hash(context);
        batch.recordContext(context);
        List<WorkerWorldSettingComparisonContextResponse.Target> targets = new ArrayList<>();
        context.path("targets").forEach(target -> targets.add(stateMapper.toTarget(target)));
        List<WorkerWorldSettingComparisonBatchContextResponse.ExactTarget> exact = group.stream()
                .map(candidate -> new WorkerWorldSettingComparisonBatchContextResponse.ExactTarget(
                        candidate.getComparisonCandidateRef(),
                        mapper.toUuidList(candidate.getResolvedTargetWorldSettingIds()).size() == 1
                                ? mapper.toUuidList(candidate.getResolvedTargetWorldSettingIds()).getFirst() : null,
                        candidate.getProvisionalSubjectKey())).toList();
        return new WorkerWorldSettingComparisonBatchContextResponse(batch.getId(),
                mapper.toComparisonBatchCandidates(group), exact, targets, token, contextMapper.toResponse(job, includeReferenceSelection));
    }

    public void completeBatch(AnalysisJob job, UUID batchId, WorkerWorldSettingComparisonBatchCompleteRequest request) {
        WorldSettingComparisonBatch batch = getBatch(job, batchId);
        String completionHash = journal.hash(mapper.toCompletionFingerprintPayload(request));
        if (batch.isCompletedWith(completionHash)) {
            return;
        }
        requireProcessing(batch);
        List<WorldSettingCandidate> group = processingCandidates(batch);
        JsonNode stored = batch.getContextSnapshotJson();
        if (stored == null || request.contextToken() == null
                || !request.contextToken().equals(journal.hash(stored))
                || !request.contextToken().equals(journal.hash(contextState(job, batch, batchTargetRefs(batch))))) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_CONTEXT_STALE);
        }
        Set<String> versionRefs = new HashSet<>();
        for (var version : request.contextVersions()) {
            String ref = reference(version.worldSettingId(), version.provisionalSubjectKey());
            if (!versionRefs.add(ref) || !stored.path("targets").has(ref)
                    || stored.path("targets").path(ref).path("version").asLong() != version.version()) {
                throw invalid();
            }
        }
        if (!versionRefs.equals(batchTargetRefs(batch))) {
            throw invalid();
        }
        Map<String, WorldSettingCandidate> byRef = new LinkedHashMap<>();
        group.forEach(candidate -> byRef.put(candidate.getComparisonCandidateRef(), candidate));
        Set<String> covered = new HashSet<>();
        for (var decision : request.decisions()) {
            for (String ref : decision.sourceCandidateRefs()) {
                if (!byRef.containsKey(ref) || !covered.add(ref)) throw invalid();
            }
        }
        JsonNode commonDiagnostics = WorldSettingComparisonDiagnostics.validate(request.diagnostics(),
                byRef.keySet(), stored.path("targets"));
        Map<String, JsonNode> failureDiagnostics = new LinkedHashMap<>();
        for (var failure : request.failures()) {
            if (failure.sourceCandidateRefs().isEmpty() || failure.failureCode() == null
                    || !failure.failureCode().isCandidateComparisonFailure()
                    || failure.errorMessage() == null || failure.errorMessage().isBlank()
                    || failure.errorMessage().length() > 1000) throw invalid();
            for (String ref : failure.sourceCandidateRefs()) {
                if (!byRef.containsKey(ref) || !covered.add(ref)) throw invalid();
            }
            var diagnostics = WorldSettingComparisonDiagnostics.validate(failure.diagnostics(),
                    new HashSet<>(failure.sourceCandidateRefs()), stored.path("targets"));
            failure.sourceCandidateRefs().forEach(ref -> failureDiagnostics.put(ref, diagnostics));
        }
        if (!covered.equals(byRef.keySet())) throw invalid();
        Map<String, JsonNode> candidateDiagnostics = new LinkedHashMap<>();
        for (String ref : byRef.keySet()) {
            Set<JsonNode> distinctDiagnostics = new LinkedHashSet<>();
            for (JsonNode entry : commonDiagnostics) {
                if (entry.path("candidateRefs").isEmpty() || java.util.stream.StreamSupport
                        .stream(entry.path("candidateRefs").spliterator(), false).anyMatch(value -> ref.equals(value.asText()))) distinctDiagnostics.add(entry);
            }
            for (JsonNode entry : failureDiagnostics.getOrDefault(ref, JsonNodeFactory.instance.arrayNode())) {
                distinctDiagnostics.add(entry);
            }
            var orderedDiagnostics = distinctDiagnostics.stream().sorted(Comparator.comparingInt(entry -> entry.path("attempt").asInt())).toList();
            var diagnostics = JsonNodeFactory.instance.arrayNode();
            orderedDiagnostics.stream().skip(Math.max(0, orderedDiagnostics.size() - 30)).forEach(entry -> diagnostics.add(entry.deepCopy()));
            candidateDiagnostics.put(ref, diagnostics);
        }
        Set<String> consumed = new HashSet<>();
        Set<String> decisionRefs = new HashSet<>();
        Set<String> writtenPaths = new HashSet<>();
        ObjectNode projected = stateService.getProjectedState(job).deepCopy();
        List<AnalysisStateChange> changes = new ArrayList<>();
        List<WorldSettingComparisonDecisionSource> sourceRows = new ArrayList<>();
        for (var input : request.decisions()) {
            if (!decisionRefs.add(input.decisionRef()) || input.sourceCandidateRefs().isEmpty()) {
                throw invalid();
            }
            List<WorldSettingCandidate> decisionSources = new ArrayList<>();
            for (String sourceRef : input.sourceCandidateRefs()) {
                if (!consumed.add(sourceRef) || !byRef.containsKey(sourceRef)) {
                    throw invalid();
                }
                decisionSources.add(byRef.get(sourceRef));
            }
            String targetRef = reference(input.targetWorldSettingId(), input.provisionalSubjectKey());
            JsonNode before = stored.path("targets").path(targetRef);
            if (!before.isObject()) {
                throw invalid();
            }
            validateProposal(batch, decisionSources, before, input);
            WorldSettingPropertyView properties = new WorldSettingPropertyView(before.path("propertiesJson"));
            List<WorldSettingComparisonDecision.ExistingRootPropertyMoveSnapshot> moves = new ArrayList<>();
            for (String root : input.existingRootPropertyNamesToMove() == null ? List.<String>of()
                    : input.existingRootPropertyNamesToMove()) {
                String key = targetRef + "|" + WorldSettingAnalysisStateMapper.pathKey(null,
                        WorldSettingNameNormalizer.duplicateKey(root));
                if (!writtenPaths.add(key)) {
                    throw invalid();
                }
                moves.add(new WorldSettingComparisonDecision.ExistingRootPropertyMoveSnapshot(
                        properties.path(null, root).settingName(), properties.value(null, root)));
            }
            if (isApplied(input)) {
                String path = targetRef + "|" + WorldSettingAnalysisStateMapper.pathKey(
                        normalized(input.proposedScopeName()), WorldSettingNameNormalizer.duplicateKey(input.proposedSettingName()));
                if (!writtenPaths.add(path)) {
                    throw invalid();
                }
            }
            WorldSetting actual = input.targetWorldSettingId() == null ? null
                    : settings.findByIdAndWorkId(input.targetWorldSettingId(), job.getWork().getId()).orElseThrow(this::invalid);
            String beforeValue = input.matchedPropertyName() == null ? null
                    : properties.value(input.matchedScopeName(), input.matchedPropertyName());
            WorldSettingComparisonDecision decision = WorldSettingComparisonDecision.create(batch, input.decisionRef(),
                    input.canonicalSubjectName(), actual, input.matchedScopeName(), input.matchedPropertyName(),
                    input.consolidationStatus(), input.suggestedOperation(), input.comparisonReviewReason(),
                    input.proposedScopeName(), input.proposedSettingName(), beforeValue, input.proposedValue(),
                    input.comparisonReason(), moves, mapper.toJsonNode(input.rawComparisonJson()));
            decision.bindOrderedTarget(input.provisionalSubjectKey(), before.path("version").asLong());
            decisions.saveAndFlush(decision);
            for (int index = 0; index < decisionSources.size(); index++) {
                WorldSettingCandidate candidate = decisionSources.get(index);
                candidate.completeComparison(decision, LocalDateTime.now());
                candidate.recordComparisonDiagnostics(candidateDiagnostics.get(candidate.getComparisonCandidateRef()));
                sourceRows.add(WorldSettingComparisonDecisionSource.create(batch, decision, candidate,
                        candidate.getComparisonCandidateRef(), index));
            }
            List<AnalysisStateChange> decisionChanges = decisionChanges(job, batch, decisionSources, decision, input,
                    targetRef, projected);
            changes.addAll(decisionChanges);
            projected = (ObjectNode) journal.apply(projected, decisionChanges);
        }
        for (var failure : request.failures()) {
            for (String ref : failure.sourceCandidateRefs()) {
                if (!consumed.add(ref)) throw invalid();
                var candidate = byRef.get(ref);
                candidate.failComparison(failure.failureCode(), failure.errorMessage());
                candidate.recordComparisonFailure(job, batch.getId(), failureDiagnosticsSanitizer.sanitize(failure.failureDiagnostics(), job.getWork().getId()));
                candidate.recordComparisonDiagnostics(candidateDiagnostics.get(ref));
                if (job.isAutomaticReview()) changes.add(failedComparisonReference(candidate));
            }
        }
        if (!consumed.equals(byRef.keySet())) {
            throw invalid();
        }
        validateSyntheticScopes(batch, stored.path("targets"), projected.path("worldSettings"), request.decisions());
        sources.saveAll(sourceRows);
        stateService.appendValidatedChanges(job, job.getInputStateHash(), changes);
        batch.complete(completionHash, mapper.toJsonNode(request.rawComparisonJson()));
    }

    private void validateProposal(WorldSettingComparisonBatch batch, List<WorldSettingCandidate> source,
            JsonNode target, WorkerWorldSettingComparisonBatchCompleteRequest.Decision input) {
        if (!sameName(target.path("subjectName").asText(), input.canonicalSubjectName())
                || source.size() > 1 && input.consolidationStatus() == WorldSettingConsolidationStatus.SINGLE
                || source.stream().anyMatch(candidate -> candidate.getCategory() != batch.getCategory()
                || !sameName(candidate.getScopeName(), batch.getRawScopeName()))) {
            throw invalid();
        }
        var property = new WorldSettingPropertyView(target.path("propertiesJson"));
        var operation = input.suggestedOperation();
        List<String> moves = input.existingRootPropertyNamesToMove() == null ? List.of()
                : input.existingRootPropertyNamesToMove();
        if (operation != WorldSettingSuggestedOperation.ADD && !moves.isEmpty()) {
            throw invalid();
        }
        if (operation == WorldSettingSuggestedOperation.REVIEW_REQUIRED) {
            if (input.comparisonReviewReason() == WorldSettingComparisonReviewReason.BATCH_LIMIT_EXCEEDED) {
                if (source.size() != 1 || input.matchedScopeName() != null || input.matchedPropertyName() != null) {
                    throw invalid();
                }
                var original = source.getFirst();
                long valueCount = java.util.Arrays.stream(original.getExtractedValue().split("\\R", -1))
                        .map(String::strip).filter(value -> !value.isEmpty()).count();
                var expected = valueCount > 1 ? WorldSettingConsolidationStatus.CONFLICT : WorldSettingConsolidationStatus.SINGLE;
                if (input.consolidationStatus() != expected
                        || !Objects.equals(original.getScopeName(), input.proposedScopeName())
                        || !Objects.equals(original.getSettingName(), input.proposedSettingName())
                        || !Objects.equals(original.getExtractedValue(), input.proposedValue())) {
                    throw invalid();
                }
                return;
            }
            if (input.consolidationStatus() == WorldSettingConsolidationStatus.CONFLICT
                    && input.comparisonReviewReason() == WorldSettingComparisonReviewReason.GENERAL_UNCERTAINTY) {
                var matched = property.path(input.matchedScopeName(), input.matchedPropertyName());
                if (source.size() != 1 || matched == null
                        || !sameName(matched.scopeName(), input.proposedScopeName())
                        || !sameName(matched.settingName(), input.proposedSettingName())
                        || !sameName(source.getFirst().getScopeName(), matched.scopeName())
                        || source.getFirst().getTemporalScope() != org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope.PRESENT
                        || source.getFirst().getAnalysisJob().isSettingBookSource()
                            && !org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingExtractionAssertion.isAsserted(source.getFirst().getRawExtractionJson())
                        || org.monitoring.catchholebackend.domain.analysis.processor.SettingBookTemporalPolicy.requiresReview(
                                org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.from(source.getFirst().getAnalysisJob(),
                                        source.getFirst().getEffectiveFromEpisodeNo(), source.getFirst().getTemporalScope()), List.of(), false)) {
                    throw invalid();
                }
                var times = org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.fromJson(
                        target.path("provenanceByPath").path(WorldSettingAnalysisStateMapper.pathKey(matched.scopeName(), matched.settingName())).path("currentSourceTimes"));
                if (times.isEmpty() || times.stream().anyMatch(time -> time.temporalScope() != org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope.PRESENT)) {
                    throw invalid();
                }
                return;
            }
            if (input.comparisonReviewReason() == WorldSettingComparisonReviewReason.GENERAL_UNCERTAINTY) {
                if (source.size() != 1
                        || !sameName(source.getFirst().getScopeName(), input.proposedScopeName())
                        || !sameName(source.getFirst().getSettingName(), input.proposedSettingName())
                        || !Objects.equals(source.getFirst().getExtractedValue(), input.proposedValue())
                        || input.matchedPropertyName() == null && input.matchedScopeName() != null
                        || input.matchedPropertyName() != null
                            && property.path(input.matchedScopeName(), input.matchedPropertyName()) == null) {
                    throw invalid();
                }
                return;
            }
            if (input.comparisonReviewReason() == WorldSettingComparisonReviewReason.SCOPE_MISMATCH) {
                if (source.size() != 1 || source.getFirst().getScopeName() == null
                        || input.matchedPropertyName() == null
                        || property.path(input.matchedScopeName(), input.matchedPropertyName()) == null
                        || sameName(source.getFirst().getScopeName(), input.matchedScopeName())
                        || !sameName(source.getFirst().getScopeName(), input.proposedScopeName())
                        || !sameName(source.getFirst().getSettingName(), input.proposedSettingName())) {
                    throw invalid();
                }
                return;
            }
            if (input.comparisonReviewReason() != WorldSettingComparisonReviewReason.SCOPE_UNRESOLVED
                    || source.stream().anyMatch(candidate -> candidate.getScopeName() != null)
                    || input.matchedScopeName() == null
                    || property.path(input.matchedScopeName(), input.matchedPropertyName()) == null) {
                throw invalid();
            }
            if (source.stream().anyMatch(candidate -> !sameName(candidate.getSettingName(), input.matchedPropertyName()))
                    && (source.size() != 1 || input.proposedScopeName() != null
                    || !sameName(source.getFirst().getSettingName(), input.proposedSettingName()))) {
                throw invalid();
            }
            return;
        }
        if (input.comparisonReviewReason() != null) {
            throw invalid();
        }
        if (operation == WorldSettingSuggestedOperation.UPDATE || operation == WorldSettingSuggestedOperation.MERGE) {
            var matched = property.path(input.matchedScopeName(), input.matchedPropertyName());
            if (matched == null || !sameName(matched.scopeName(), input.proposedScopeName())
                    || !sameName(matched.settingName(), input.proposedSettingName())) {
                throw invalid();
            }
        } else if (operation == WorldSettingSuggestedOperation.ADD) {
            if (input.matchedPropertyName() != null || input.matchedScopeName() != null
                    || property.path(input.proposedScopeName(), input.proposedSettingName()) != null
                    || property.conflicts(input.proposedScopeName(), input.proposedSettingName())
                    || input.proposedScopeName() != null && sameName(input.proposedScopeName(), input.proposedSettingName())) {
                throw invalid();
            }
            Set<String> uniqueMoves = new HashSet<>();
            for (String root : moves) {
                if (input.proposedScopeName() == null || !uniqueMoves.add(WorldSettingNameNormalizer.duplicateKey(root))
                        || property.path(null, root) == null || property.path(input.proposedScopeName(), root) != null
                        || property.conflicts(input.proposedScopeName(), root)
                        || sameName(root, input.proposedSettingName())) {
                    throw invalid();
                }
            }
        } else if (operation == WorldSettingSuggestedOperation.EXCLUDE) {
            if (input.matchedPropertyName() != null
                    && property.path(input.matchedScopeName(), input.matchedPropertyName()) == null
                    || input.matchedPropertyName() == null && input.matchedScopeName() != null) {
                throw invalid();
            }
        } else {
            throw invalid();
        }
    }

    private List<AnalysisStateChange> decisionChanges(AnalysisJob job, WorldSettingComparisonBatch batch,
            List<WorldSettingCandidate> source, WorldSettingComparisonDecision decision,
            WorkerWorldSettingComparisonBatchCompleteRequest.Decision input, String targetRef, JsonNode projected) {
        JsonNode target = projected.path("worldSettings").path(targetRef);
        String prefix = "world-decision:" + batch.getId() + ":" + input.decisionRef();
        List<UUID> sourceIds = source.stream().map(WorldSettingCandidate::getId).toList();
        var hold = job.isAutomaticReview() && job.isSettingBookSource() && source.stream().anyMatch(candidate ->
                !org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingExtractionAssertion.isAsserted(candidate.getRawExtractionJson()))
                ? org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.REVIEW_REQUIRED
                : source.stream().anyMatch(candidate -> org.monitoring.catchholebackend.domain.analysis.processor.SettingBookTemporalPolicy.requiresReview(
                org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.from(
                        candidate.getAnalysisJob(), candidate.getEffectiveFromEpisodeNo(), candidate.getTemporalScope()), List.of(), false))
                ? org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.SETTING_TIME_CONFIRMATION_REQUIRED
                : isApplied(input) ? projectedHold(source, decision, input, target) : null;
        if (job.isAutomaticReview() && isApplied(input)) {
            for (JsonNode reference : projected.path("references")) {
                if (targetRef.equals(reference.path("targetRef").asText())
                        && "CONFLICT".equals(reference.path("consolidationStatus").asText())
                        && (reference.path("comparisonReviewReason").isMissingNode()
                            || "GENERAL_UNCERTAINTY".equals(reference.path("comparisonReviewReason").asText()))
                        && "REVIEW_REQUIRED".equals(reference.path("automaticReviewHoldReason").asText())
                        && org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingCurrentSources.samePath(
                                reference.path("scopeName").isNull() ? null : reference.path("scopeName").asText(), reference.path("settingName").asText(),
                                input.proposedScopeName(), input.proposedSettingName())) {
                    hold = org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.DEPENDENCY_CONFIRMATION_REQUIRED;
                }
            }
        }
        if (!isApplied(input) || hold != null) {
            var heldReason = hold;
            if (heldReason != null) source.forEach(candidate -> candidate.recordAutomaticReviewHold(heldReason));
            if (job.isAutomaticReview() && hold == null && input.consolidationStatus() == WorldSettingConsolidationStatus.CONFLICT
                    && (input.comparisonReviewReason() == null
                        || input.suggestedOperation() == WorldSettingSuggestedOperation.REVIEW_REQUIRED
                            && input.comparisonReviewReason() == WorldSettingComparisonReviewReason.GENERAL_UNCERTAINTY)
                    && source.stream().allMatch(candidate -> candidate.getTemporalScope() == org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope.PRESENT
                        && (!candidate.getAnalysisJob().isSettingBookSource()
                            || org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingExtractionAssertion.isAsserted(candidate.getRawExtractionJson())))) {
                List<UUID> heldIds = new ArrayList<>(sourceIds);
                source.forEach(candidate -> candidate.recordAutomaticReviewHold(
                        org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.REVIEW_REQUIRED));
                var view = new WorldSettingPropertyView(target.path("propertiesJson"));
                var path = view.path(input.proposedScopeName(), input.proposedSettingName());
                if (path != null) {
                    var affected = new LinkedHashMap<String, String[]>();
                    affected.put(WorldSettingAnalysisStateMapper.pathKey(normalized(path.scopeName()), normalized(path.settingName())), new String[]{path.scopeName(), path.settingName()});
                    for (int index = 0; index < affected.size(); index++) {
                        String[] names = new ArrayList<>(affected.values()).get(index);
                        var currentPath = view.path(names[0], names[1]);
                        if (currentPath == null) continue;
                        String key = WorldSettingAnalysisStateMapper.pathKey(currentPath.scopeName(), currentPath.settingName());
                        for (JsonNode id : target.path("provenanceByPath").path(key).path("sourceCandidateIds")) {
                            UUID candidateId = UUID.fromString(id.asText());
                            if (heldIds.contains(candidateId)) continue;
                            WorldSettingCandidate prior = candidates.findByIdAndWorkIdForUpdate(candidateId, job.getWork().getId()).orElseThrow(this::invalid);
                            var comparison = prior.getComparisonDecision();
                            if (prior.getAnalysisJob() == null || !prior.getAnalysisJob().getId().equals(job.getId())
                                    || !targetRef.equals(prior.getCanonicalSubjectKey()) || comparison == null) continue;
                            boolean writesPath = org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingCurrentSources.samePath(
                                    prior.getProposedScopeName(), prior.getProposedSettingName(), currentPath.scopeName(), currentPath.settingName())
                                    || !comparison.isRootPropertyMovesDisabled() && comparison.getExistingRootPropertyMoveSnapshots().stream().anyMatch(move ->
                                        org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingCurrentSources.samePath(
                                                comparison.getProposedScopeName(), move.settingName(), currentPath.scopeName(), currentPath.settingName()));
                            if (!writesPath) continue;
                            prior.recordAutomaticReviewHold(index == 0 ? org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.REVIEW_REQUIRED
                                    : org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.DEPENDENCY_CONFIRMATION_REQUIRED);
                            heldIds.add(candidateId);
                            affected.putIfAbsent(WorldSettingAnalysisStateMapper.pathKey(normalized(comparison.getProposedScopeName()), normalized(comparison.getProposedSettingName())),
                                    new String[]{comparison.getProposedScopeName(), comparison.getProposedSettingName()});
                            if (!comparison.isRootPropertyMovesDisabled()) for (var move : comparison.getExistingRootPropertyMoveSnapshots()) {
                                affected.putIfAbsent(WorldSettingAnalysisStateMapper.pathKey(null, normalized(move.settingName())), new String[]{null, move.settingName()});
                                affected.putIfAbsent(WorldSettingAnalysisStateMapper.pathKey(normalized(comparison.getProposedScopeName()), normalized(move.settingName())), new String[]{comparison.getProposedScopeName(), move.settingName()});
                            }
                        }
                    }
                    JsonNode baseline = stateService.getInputState(job).path("worldSettings").path(targetRef);
                    ObjectNode restored = target.deepCopy();
                    ObjectNode properties = restored.path("propertiesJson").deepCopy();
                    var original = new WorldSettingPropertyView(baseline.has("propertiesJson") ? baseline.path("propertiesJson") : JsonNodeFactory.instance.objectNode());
                    for (String[] names : affected.values()) {
                        var currentPath = view.path(names[0], names[1]);
                        if (currentPath != null) {
                            if (currentPath.scopeName() == null) properties.remove(currentPath.settingName());
                            else {
                                ObjectNode scope = (ObjectNode) properties.path(currentPath.scopeName()); scope.remove(currentPath.settingName());
                                if (scope.isEmpty()) properties.remove(currentPath.scopeName());
                            }
                            restored.withObject("provenanceByPath").remove(WorldSettingAnalysisStateMapper.pathKey(currentPath.scopeName(), currentPath.settingName()));
                        }
                        var originalPath = original.path(names[0], names[1]);
                        if (originalPath != null) {
                            var restoredView = new WorldSettingPropertyView(properties);
                            restoredView.upsert(originalPath.scopeName(), originalPath.settingName(), original.value(names[0], names[1]));
                            properties = (ObjectNode) restoredView.toJson();
                            String originalKey = WorldSettingAnalysisStateMapper.pathKey(originalPath.scopeName(), originalPath.settingName());
                            restored.withObject("provenanceByPath").set(originalKey, baseline.path("provenanceByPath").path(originalKey).deepCopy());
                        }
                    }
                    restored.set("propertiesJson", properties);
                    restored.put("version", restored.path("version").asLong() + 1);
                    return List.of(new AnalysisStateChange(prefix + ":restore-path", List.of("worldSettings", targetRef), restored, false,
                                    "REVIEW_REQUIRED", heldIds), referenceChange(prefix, targetRef, source, decision));
                }
            }
            return List.of(referenceChange(prefix, targetRef, source, decision));
        }
        ObjectNode updated = target.deepCopy();
        WorldSettingPropertyView view = new WorldSettingPropertyView(updated.path("propertiesJson"));
        for (var move : decision.getExistingRootPropertyMoveSnapshots()) {
            view.moveRoot(move.settingName(), input.proposedScopeName(), move.beforeValue());
        }
        view.upsert(input.proposedScopeName(), input.proposedSettingName(), input.proposedValue());
        updated.set("propertiesJson", view.toJson());
        updated.put("version", updated.path("version").asLong() + 1);
        ObjectNode provenance = provenance(source.getFirst());
        var lineage = new LinkedHashSet<UUID>();
        var priorPath = new WorldSettingPropertyView(target.path("propertiesJson")).path(input.proposedScopeName(), input.proposedSettingName());
        if (priorPath != null) target.path("provenanceByPath").path(WorldSettingAnalysisStateMapper.pathKey(priorPath.scopeName(), priorPath.settingName()))
                .path("sourceCandidateIds").forEach(id -> lineage.add(UUID.fromString(id.asText())));
        lineage.addAll(sourceIds);
        provenance.set("sourceCandidateIds", mapper.toJsonNode(lineage));
        var sourceTimes = provenance.putArray("currentSourceTimes");
        if (input.suggestedOperation() == WorldSettingSuggestedOperation.MERGE) {
            var oldPath = view.path(input.proposedScopeName(), input.proposedSettingName());
            var previous = target.path("provenanceByPath").path(WorldSettingAnalysisStateMapper.pathKey(oldPath.scopeName(), oldPath.settingName())).path("currentSourceTimes");
            if (previous.isArray()) previous.forEach(time -> sourceTimes.add(time.deepCopy()));
        }
        for (var candidate : source) sourceTimes.add(org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.toJson(
                org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.from(candidate.getAnalysisJob(), candidate.getEffectiveFromEpisodeNo(), candidate.getTemporalScope())));
        provenance.put("currentValueProtected", false);
        var writtenPath = view.path(input.proposedScopeName(), input.proposedSettingName());
        updated.withObject("provenanceByPath").set(WorldSettingAnalysisStateMapper.pathKey(
                writtenPath.scopeName(), writtenPath.settingName()), provenance);
        for (var move : decision.getExistingRootPropertyMoveSnapshots()) {
            var originalPath = new WorldSettingPropertyView(target.path("propertiesJson")).path(null, move.settingName());
            String originalKey = WorldSettingAnalysisStateMapper.pathKey(originalPath.scopeName(), originalPath.settingName());
            JsonNode originalProvenance = target.path("provenanceByPath").path(originalKey);
            updated.withObject("provenanceByPath").remove(originalKey);
            var movedPath = view.path(input.proposedScopeName(), move.settingName());
            ObjectNode movedProvenance = originalProvenance.deepCopy();
            var movedIds = new LinkedHashSet<UUID>();
            originalProvenance.path("sourceCandidateIds").forEach(id -> movedIds.add(UUID.fromString(id.asText())));
            movedIds.addAll(sourceIds);
            movedProvenance.set("sourceCandidateIds", mapper.toJsonNode(movedIds));
            updated.withObject("provenanceByPath").set(WorldSettingAnalysisStateMapper.pathKey(
                    movedPath.scopeName(), movedPath.settingName()), movedProvenance);
        }
        return List.of(new AnalysisStateChange(prefix, List.of("worldSettings", targetRef), updated, false,
                input.suggestedOperation().name(), sourceIds));
    }

    private org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason projectedHold(
            List<WorldSettingCandidate> source, WorldSettingComparisonDecision decision,
            WorkerWorldSettingComparisonBatchCompleteRequest.Decision input, JsonNode target) {
        WorldSettingPropertyView view = new WorldSettingPropertyView(target.path("propertiesJson"));
        var affected = new LinkedHashMap<String, String[]>();
        affected.put("destination", new String[] {input.proposedScopeName(), input.proposedSettingName()});
        if (input.matchedPropertyName() != null && !org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingCurrentSources.samePath(
                input.matchedScopeName(), input.matchedPropertyName(), input.proposedScopeName(), input.proposedSettingName())) {
            affected.put("source", new String[] {input.matchedScopeName(), input.matchedPropertyName()});
        }
        for (var move : decision.getExistingRootPropertyMoveSnapshots()) {
            affected.put("root:"+move.settingName(), new String[] {null, move.settingName()});
            affected.put("moved:"+move.settingName(), new String[] {input.proposedScopeName(), move.settingName()});
        }
        for (var candidate : source) {
            var incoming = org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.from(candidate.getAnalysisJob(), candidate.getEffectiveFromEpisodeNo(), candidate.getTemporalScope());
            if (org.monitoring.catchholebackend.domain.analysis.processor.SettingBookTemporalPolicy.requiresReview(incoming, List.of(), false)) {
                return org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.SETTING_TIME_CONFIRMATION_REQUIRED;
            }
            for (var affectedPath : affected.entrySet()) {
                var path = affectedPath.getValue();
                var property = view.path(path[0], path[1]);
                boolean changes = !affectedPath.getKey().equals("destination") || !Objects.equals(view.value(path[0], path[1]), input.proposedValue());
                changes |= candidate.getAnalysisJob().isAutomaticReview() && input.suggestedOperation() == WorldSettingSuggestedOperation.MERGE;
                if (!changes) continue;
                boolean protectedPath = stateMapper.protectedPaths(target).stream().anyMatch(marker ->
                        org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingCurrentSources.samePath(marker.scopeName(), marker.settingName(), path[0], path[1]));
                JsonNode provenance = property == null ? JsonNodeFactory.instance.objectNode() : target.path("provenanceByPath").path(WorldSettingAnalysisStateMapper.pathKey(property.scopeName(), property.settingName()));
                if (candidate.getAnalysisJob().isSettingBookSource() && (protectedPath || provenance.path("currentValueProtected").asBoolean())) {
                    return org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.HUMAN_CURRENT_SETTING_PROTECTED;
                }
                if (property != null && org.monitoring.catchholebackend.domain.analysis.processor.SettingBookTemporalPolicy.requiresReview(incoming,
                        org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.fromJson(provenance.path("currentSourceTimes")),
                        !candidate.getAnalysisJob().isAutomaticReview() || !affectedPath.getKey().equals("destination")
                                || input.suggestedOperation() != WorldSettingSuggestedOperation.MERGE,
                        candidate.getAnalysisJob().isAutomaticReview() && affectedPath.getKey().equals("destination"))) {
                    return org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.SETTING_TIME_CONFIRMATION_REQUIRED;
                }
            }
        }
        return null;
    }

    private AnalysisStateChange referenceChange(String eventId, String targetRef,
            List<WorldSettingCandidate> source, WorldSettingComparisonDecision decision) {
        ObjectNode reference = JsonNodeFactory.instance.objectNode();
        reference.put("domain", "worldSettings");
        reference.put("targetRef", targetRef);
        reference.put("decisionId", decision.getId() == null ? null : decision.getId().toString());
        reference.put("category", source.getFirst().getCategory().name());
        reference.put("subjectName", decision.getCanonicalSubjectName());
        reference.put("scopeName", decision.getProposedScopeName());
        reference.put("settingName", decision.getProposedSettingName());
        reference.put("proposedValue", decision.getProposedValue());
        reference.put("consolidationStatus", decision.getConsolidationStatus().name());
        reference.put("operation", decision.getSuggestedOperation().name());
        reference.put("reason", decision.getComparisonReason());
        if (decision.getConsolidationStatus() == WorldSettingConsolidationStatus.CONFLICT) {
            reference.put("matchedScopeName", decision.getMatchedScopeName());
            reference.put("matchedPropertyName", decision.getMatchedPropertyName());
            reference.put("beforeValue", decision.getBeforeValue());
        }
        if (decision.getComparisonReviewReason() == WorldSettingComparisonReviewReason.SCOPE_MISMATCH
                || decision.getComparisonReviewReason() == WorldSettingComparisonReviewReason.SCOPE_UNRESOLVED) {
            reference.put("comparisonReviewReason", decision.getComparisonReviewReason().name());
            reference.put("matchedScopeName", decision.getMatchedScopeName());
            reference.put("matchedPropertyName", decision.getMatchedPropertyName());
            reference.put("beforeValue", decision.getBeforeValue());
        }
        if (decision.getComparisonReviewReason() == WorldSettingComparisonReviewReason.BATCH_LIMIT_EXCEEDED) {
            WorldSettingCandidate original = source.getFirst();
            reference.put("comparisonReviewReason", decision.getComparisonReviewReason().name());
            reference.put("confirmationStatus", "UNCONFIRMED");
            reference.put("subjectName", original.getSubjectName());
            reference.put("value", original.getExtractedValue());
            if (original.getEvidenceSpans() != null) {
                reference.set("evidenceSpans", original.getEvidenceSpans().deepCopy());
            }
        }
        if (decision.getComparisonReviewReason() == WorldSettingComparisonReviewReason.GENERAL_UNCERTAINTY
                && decision.getConsolidationStatus() != WorldSettingConsolidationStatus.CONFLICT) {
            // 대상 선택도 아직 검토 대상이므로 원본 주체·값을 참고로 보존한다.
            WorldSettingCandidate original = source.getFirst();
            reference.put("comparisonReviewReason", decision.getComparisonReviewReason().name());
            reference.put("confirmationStatus", "UNCONFIRMED");
            reference.remove("targetRef");
            reference.put("subjectName", original.getSubjectName());
            reference.put("scopeName", original.getScopeName());
            reference.put("settingName", original.getSettingName());
            reference.put("proposedValue", original.getExtractedValue());
            reference.put("value", original.getExtractedValue());
            if (original.getEvidenceSpans() != null) {
                reference.set("evidenceSpans", original.getEvidenceSpans().deepCopy());
            }
        }
        reference.put("sourceEpisodeNo", (source.getFirst().getSourceEpisode() == null ? null : source.getFirst().getSourceEpisode().getEpisodeNo()));
        if (source.getFirst().getAutomaticReviewHoldReason() != null) reference.put("automaticReviewHoldReason", source.getFirst().getAutomaticReviewHoldReason().name());
        reference.put("effectiveFromEpisodeNo", source.getFirst().getEffectiveFromEpisodeNo());
        reference.put("temporalScope", source.getFirst().getTemporalScope().name());
        reference.set("sourceValues", mapper.toJsonNode(source.stream().map(WorldSettingCandidate::getExtractedValue).toList()));
        List<UUID> ids = source.stream().map(WorldSettingCandidate::getId).toList();
        reference.set("sourceCandidateIds", mapper.toJsonNode(ids));
        return new AnalysisStateChange(eventId, List.of("references", eventId), reference, false,
                decision.getSuggestedOperation().name(), ids);
    }

    private void holdReviewBatch(AnalysisJob job, WorldSettingComparisonBatch batch,
            List<WorldSettingCandidate> group, WorldSettingComparisonReviewReason reviewReason) {
        List<AnalysisStateChange> changes = new ArrayList<>();
        for (int index = 0; index < group.size(); index++) {
            WorldSettingCandidate candidate = group.get(index);
            WorldSettingComparisonDecision decision = WorldSettingComparisonDecision.create(batch, "D" + (index + 1),
                    candidate.getSubjectName(), null, null, null, WorldSettingConsolidationStatus.SINGLE,
                    WorldSettingSuggestedOperation.REVIEW_REQUIRED, reviewReason,
                    candidate.getScopeName(), candidate.getSettingName(), null, candidate.getExtractedValue(),
                    reviewReason == WorldSettingComparisonReviewReason.SUBJECT_UNRESOLVED
                            ? "대상을 명확히 연결할 수 없어 사용자 검토가 필요합니다."
                            : "비교 입력 상한을 초과해 사용자 검토가 필요합니다.", mapper.toJsonNode(Map.of()));
            decisions.saveAndFlush(decision);
            candidate.completeComparison(decision, LocalDateTime.now());
            sources.save(WorldSettingComparisonDecisionSource.create(batch, decision, candidate,
                    candidate.getComparisonCandidateRef(), 0));
            changes.add(referenceChange("world-unresolved:" + candidate.getId(), null, List.of(candidate), decision));
        }
        stateService.appendValidatedChanges(job, job.getInputStateHash(), changes);
        batch.requireReview(mapper.toJsonNode(Map.of("reason", reviewReason.name())));
    }

    private void validateSyntheticScopes(WorldSettingComparisonBatch batch, JsonNode before, JsonNode after,
            List<WorkerWorldSettingComparisonBatchCompleteRequest.Decision> inputs) {
        if (batch.getRawScopeName() != null) {
            return;
        }
        for (var input : inputs) {
            if (!isApplied(input) || input.proposedScopeName() == null) {
                continue;
            }
            String ref = reference(input.targetWorldSettingId(), input.provisionalSubjectKey());
            String existingScope = WorldSettingPropertyView.findStoredFieldName(before.path(ref).path("propertiesJson"),
                    input.proposedScopeName());
            String resultingScope = WorldSettingPropertyView.findStoredFieldName(after.path(ref).path("propertiesJson"),
                    input.proposedScopeName());
            if (existingScope == null && (resultingScope == null
                    || after.path(ref).path("propertiesJson").path(resultingScope).size() < 2)) {
                throw invalid();
            }
        }
    }

    private boolean isApplied(WorkerWorldSettingComparisonBatchCompleteRequest.Decision input) {
        return input.consolidationStatus() != WorldSettingConsolidationStatus.CONFLICT
                && (input.suggestedOperation() == WorldSettingSuggestedOperation.ADD
                || input.suggestedOperation() == WorldSettingSuggestedOperation.UPDATE
                || input.suggestedOperation() == WorldSettingSuggestedOperation.MERGE);
    }

    private String reference(UUID actualId, String provisionalKey) {
        if ((actualId == null) == (provisionalKey == null)) {
            throw invalid();
        }
        return actualId == null ? provisionalKey : WorldSettingAnalysisStateMapper.persistedRef(actualId);
    }

    private String normalized(String value) {
        return value == null ? null : WorldSettingNameNormalizer.duplicateKey(value);
    }

    private List<WorldSettingCandidate> pendingCandidates(AnalysisJob job) {
        return candidates.findSubjectResolutionCandidatesForUpdate(job.getId(), WorldSettingReviewStatus.PENDING_REVIEW,
                WorldSettingComparisonStatus.PENDING);
    }

    private void registerIdentity(AnalysisRunStateService.PreparedState prepared, WorldSettingCategory category, String ref,
            Map<UUID, WorldSettingCandidate> pending) {
        if (!ref.startsWith("provisional-world:")) {
            throw invalid();
        }
        UUID sourceId;
        try {
            sourceId = UUID.fromString(ref.substring("provisional-world:".length()));
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
        WorldSettingCandidate source = pending.get(sourceId);
        if (source == null || source.getCategory() != category) {
            throw invalid();
        }
        ObjectNode identity = JsonNodeFactory.instance.objectNode();
        identity.putNull("actualWorldSettingId");
        identity.put("provisionalSubjectKey", ref);
        identity.put("category", category.name());
        identity.put("subjectName", source.getSubjectName());
        identity.put("normalizedSubjectName", WorldSettingNameNormalizer.duplicateKey(source.getSubjectName()));
        identity.put("version", 0);
        identity.putObject("propertiesJson");
        identity.putObject("provenanceByPath");
        identity.set("provenance", provenance(source));
        identity.set("identityEvidence", source.getEvidenceSpans() == null ? null : source.getEvidenceSpans().deepCopy());
        prepared.append(List.of(new AnalysisStateChange(
                "world-identity:" + sourceId, List.of("worldSettings", ref), identity, false, "IDENTITY", List.of(sourceId))));
    }

    private ObjectNode contextState(AnalysisJob job, WorldSettingComparisonBatch batch, Set<String> refs) {
        return contextState(job, batch.getId(), batch.getCategory(), refs);
    }

    private ObjectNode contextState(AnalysisJob job, UUID batchId, WorldSettingCategory category, Set<String> refs) {
        ObjectNode context = JsonNodeFactory.instance.objectNode();
        context.put("inputStateHash", job.getInputStateHash());
        context.put("runId", job.getAnalysisRunId().toString());
        context.put("generation", job.getRunGeneration());
        context.put("batchId", batchId.toString());
        ObjectNode selected = context.putObject("targets");
        JsonNode state = stateService.getProjectedState(job).path("worldSettings");
        refs.stream().sorted().forEach(ref -> {
            JsonNode target = state.path(ref);
            requireCategory(target, category);
            selected.set(ref, target.deepCopy());
        });
        return context;
    }

    private Set<String> targetRefs(List<UUID> ids, List<String> provisional) {
        Set<String> refs = new LinkedHashSet<>();
        ids.forEach(id -> refs.add(WorldSettingAnalysisStateMapper.persistedRef(id)));
        refs.addAll(provisional);
        if (refs.size() != ids.size() + provisional.size() || refs.size() > 20) {
            throw invalid();
        }
        return refs;
    }

    private Set<String> batchTargetRefs(WorldSettingComparisonBatch batch) {
        return targetRefs(mapper.toUuidList(batch.getResolvedTargetWorldSettingIds()),
                stringList(batch.getResolvedProvisionalSubjectKeys()));
    }

    private List<String> stringList(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private void requireCategory(JsonNode target, WorldSettingCategory category) {
        if (!target.isObject() || !target.path("propertiesJson").isObject()
                || !category.name().equals(target.path("category").asText())) {
            throw invalid();
        }
    }

    private WorldSettingComparisonBatch getBatch(AnalysisJob job, UUID batchId) {
        return batches.findByIdAndWorkIdForUpdate(batchId, job.getWork().getId())
                .filter(batch -> batch.getAnalysisJob().getId().equals(job.getId())).orElseThrow(this::invalid);
    }

    private List<WorldSettingCandidate> processingCandidates(WorldSettingComparisonBatch batch) {
        List<WorldSettingCandidate> group = candidates.findAllByComparisonBatchIdOrderByCreatedAtAscIdAsc(batch.getId());
        if (group.size() != batch.getCandidateCount() || group.stream().anyMatch(candidate ->
                candidate.getComparisonStatus() != WorldSettingComparisonStatus.PROCESSING)) {
            throw invalid();
        }
        return group;
    }

    private void requireProcessing(WorldSettingComparisonBatch batch) {
        if (!batch.isProcessing()) {
            throw invalid();
        }
    }

    private boolean sameName(String left, String right) {
        return left == null || right == null ? left == right
                : WorldSettingNameNormalizer.duplicateKey(left).equals(WorldSettingNameNormalizer.duplicateKey(right));
    }

    private ObjectNode provenance(WorldSettingCandidate source) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("confirmationStatus", "PROVISIONAL");
        node.put("sourceEpisodeNo", (source.getSourceEpisode() == null ? null : source.getSourceEpisode().getEpisodeNo()));
        org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisSourceMetadata.put(node, source.getAnalysisJob());
        node.putArray("sourceCandidateIds").add(source.getId().toString());
        node.put("effectiveFromEpisodeNo", source.getEffectiveFromEpisodeNo());
        return node;
    }

    private AppException invalid() {
        return new AppException(WorldSettingErrorCode.WORLD_SETTING_WORKER_JOB_INVALID);
    }
}
