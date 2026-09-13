package org.monitoring.catchholebackend.domain.worldsetting.service;

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
        reference.put("sourceEpisodeNo", candidate.getSourceEpisode().getEpisodeNo());
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
        return new WorkerWorldSettingSubjectResolutionPendingResponse(pendingCandidates(job).stream()
                .filter(candidate -> !candidate.hasSubjectResolution())
                .map(candidate -> new WorkerWorldSettingSubjectResolutionPendingResponse.Candidate(
                        candidate.getId(), candidate.getSourceEpisode().getId(), candidate.getCategory(),
                        candidate.getSubjectName(), mapper.toResponse(candidate).evidenceSpans(),
                        candidate.getSourceEpisode().getEpisodeNo())).toList(),
                contextMapper.toResponse(job));
    }

    public WorkerWorldSettingSubjectResolutionResponse resolveSubjects(AnalysisJob job,
            WorkerWorldSettingSubjectResolutionRequest request) {
        Map<UUID, WorldSettingCandidate> pending = new LinkedHashMap<>();
        pendingCandidates(job).stream().filter(candidate -> !candidate.hasSubjectResolution())
                .forEach(candidate -> pending.put(candidate.getId(), candidate));
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
            JsonNode projected = stateService.getProjectedState(job).path("worldSettings");
            for (String ref : refs) {
                if (!projected.has(ref)) {
                    registerIdentity(job, candidate.getCategory(), ref, pending);
                    projected = stateService.getProjectedState(job).path("worldSettings");
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
        if (candidates.findAllByAnalysisJobIdAndComparisonStatus(job.getId(), WorldSettingComparisonStatus.FAILED)
                .stream().anyMatch(candidate -> !job.isAutomaticReview() || !candidate.canDeferFailedComparison())) {
            throw new OrderedWorldSettingComparisonClaimException();
        }
        JsonNode projected = stateService.getProjectedState(job);
        for (WorldSettingCandidate candidate : pendingCandidates(job)) {
            if (candidate.hasSubjectResolution()) {
                rejectionPolicy.match(candidate, projected).ifPresent(reference -> {
                    candidate.preservePriorHumanRejection(reference);
                    stateService.appendValidatedChanges(job, job.getInputStateHash(),
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
                    first.getSourceEpisode().getId(), first.getCategory(), first.getCanonicalSubjectKey(),
                    WorldSettingReviewStatus.PENDING_REVIEW, WorldSettingComparisonStatus.PENDING).stream()
                    .filter(candidate -> sameName(first.getScopeName(), candidate.getScopeName()))
                    .sorted(org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingCandidateChronology.comparator())
                    .toList();
            if (job.isAutomaticReview() && first.getSubjectResolutionType() != WorldSettingSubjectResolutionType.AMBIGUOUS) {
                group = boundedAutomaticGroup(job, first, group);
                if (group.isEmpty()) continue;
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
            if (group.size() > 20 || mapper.toJsonNode(mapper.toComparisonBatchCandidates(group)).toString().length() > 30000) {
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
                    batch.getSourceEpisode().getId(), batch.getCategory(), batch.getSubjectResolutionType(),
                    batch.getCanonicalSubjectKey(), batch.getCanonicalSubjectName(),
                    mapper.toUuidList(batch.getResolvedTargetWorldSettingIds()), batch.getRawScopeName(),
                    mapper.toComparisonBatchCandidates(group), stringList(batch.getResolvedProvisionalSubjectKeys()),
                    contextMapper.toResponse(job)));
        }
    }

    private List<WorldSettingCandidate> boundedAutomaticGroup(AnalysisJob job, WorldSettingCandidate first,
            List<WorldSettingCandidate> group) {
        Set<String> refs = targetRefs(mapper.toUuidList(first.getResolvedTargetWorldSettingIds()),
                stringList(first.getResolvedProvisionalSubjectKeys()));
        int contextSize = contextState(job, new UUID(0, 0), first.getCategory(), refs).toString().length();
        List<WorldSettingCandidate> selected = new ArrayList<>();
        for (WorldSettingCandidate candidate : group) {
            if (selected.size() == 20) break;
            List<WorldSettingCandidate> next = new ArrayList<>(selected);
            next.add(candidate);
            // C 참조는 startComparison 뒤 붙으므로 그 길이를 미리 포함한다.
            int inputSize = mapper.toJsonNode(mapper.toComparisonBatchCandidates(next)).toString().length()
                    + 8 * next.size();
            if (contextSize + inputSize > 30000) {
                if (selected.isEmpty()) candidate.deferComparisonInputLimit();
                break;
            }
            selected.add(candidate);
        }
        return List.copyOf(selected);
    }

    public WorkerWorldSettingComparisonBatchContextResponse getContext(AnalysisJob job, UUID batchId,
            WorkerWorldSettingComparisonBatchContextRequest request) {
        WorldSettingComparisonBatch batch = getBatch(job, batchId);
        requireProcessing(batch);
        List<WorldSettingCandidate> group = processingCandidates(batch);
        Set<String> requested = targetRefs(request.targetWorldSettingIds(), request.provisionalSubjectKeys());
        if (!requested.equals(batchTargetRefs(batch))) {
            throw invalid();
        }
        ObjectNode context = contextState(job, batch, requested);
        if (context.toString().length() + mapper.toJsonNode(mapper.toComparisonBatchCandidates(group)).toString().length() > 30000) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_COMPARISON_INPUT_LIMIT_EXCEEDED);
        }
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
                mapper.toComparisonBatchCandidates(group), exact, targets, token, contextMapper.toResponse(job));
    }

    public void completeBatch(AnalysisJob job, UUID batchId, WorkerWorldSettingComparisonBatchCompleteRequest request) {
        WorldSettingComparisonBatch batch = getBatch(job, batchId);
        String completionHash = journal.hash(mapper.toJsonNode(request));
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
        if (!request.failures().isEmpty() && !job.isAutomaticReview()) throw invalid();
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
                    targetRef, projected.path("worldSettings").path(targetRef));
            changes.addAll(decisionChanges);
            projected = (ObjectNode) journal.apply(projected, decisionChanges);
        }
        for (var failure : request.failures()) {
            for (String ref : failure.sourceCandidateRefs()) {
                if (!consumed.add(ref)) throw invalid();
                var candidate = byRef.get(ref);
                candidate.failComparison(failure.failureCode(), failure.errorMessage());
                candidate.recordComparisonDiagnostics(candidateDiagnostics.get(ref));
                changes.add(failedComparisonReference(candidate));
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
                    || !sameName(matched.settingName(), input.proposedSettingName())
                    || source.stream().anyMatch(candidate -> !sameName(candidate.getScopeName(), matched.scopeName()))) {
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
                    && (property.path(input.matchedScopeName(), input.matchedPropertyName()) == null
                    || source.stream().anyMatch(candidate -> !sameName(candidate.getScopeName(), input.matchedScopeName())))
                    || input.matchedPropertyName() == null && input.matchedScopeName() != null) {
                throw invalid();
            }
        } else {
            throw invalid();
        }
    }

    private List<AnalysisStateChange> decisionChanges(AnalysisJob job, WorldSettingComparisonBatch batch,
            List<WorldSettingCandidate> source, WorldSettingComparisonDecision decision,
            WorkerWorldSettingComparisonBatchCompleteRequest.Decision input, String targetRef, JsonNode target) {
        String prefix = "world-decision:" + batch.getId() + ":" + input.decisionRef();
        List<UUID> sourceIds = source.stream().map(WorldSettingCandidate::getId).toList();
        if (!isApplied(input)) {
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
        provenance.set("sourceCandidateIds", mapper.toJsonNode(sourceIds));
        var writtenPath = view.path(input.proposedScopeName(), input.proposedSettingName());
        updated.withObject("provenanceByPath").set(WorldSettingAnalysisStateMapper.pathKey(
                writtenPath.scopeName(), writtenPath.settingName()), provenance);
        for (var move : decision.getExistingRootPropertyMoveSnapshots()) {
            updated.withObject("provenanceByPath").remove(WorldSettingAnalysisStateMapper.pathKey(null, move.settingName()));
            var movedPath = view.path(input.proposedScopeName(), move.settingName());
            updated.withObject("provenanceByPath").set(WorldSettingAnalysisStateMapper.pathKey(
                    movedPath.scopeName(), movedPath.settingName()), provenance.deepCopy());
        }
        return List.of(new AnalysisStateChange(prefix, List.of("worldSettings", targetRef), updated, false,
                input.suggestedOperation().name(), sourceIds));
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
        if (decision.getComparisonReviewReason() == WorldSettingComparisonReviewReason.SCOPE_MISMATCH
                || decision.getComparisonReviewReason() == WorldSettingComparisonReviewReason.SCOPE_UNRESOLVED) {
            reference.put("comparisonReviewReason", decision.getComparisonReviewReason().name());
            reference.put("matchedScopeName", decision.getMatchedScopeName());
            reference.put("matchedPropertyName", decision.getMatchedPropertyName());
            reference.put("beforeValue", decision.getBeforeValue());
        }
        if (decision.getComparisonReviewReason() == WorldSettingComparisonReviewReason.GENERAL_UNCERTAINTY) {
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
        reference.put("sourceEpisodeNo", source.getFirst().getSourceEpisode().getEpisodeNo());
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

    private void registerIdentity(AnalysisJob job, WorldSettingCategory category, String ref,
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
        stateService.appendValidatedChanges(job, job.getInputStateHash(), List.of(new AnalysisStateChange(
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
        node.put("sourceEpisodeNo", source.getSourceEpisode().getEpisodeNo());
        node.putArray("sourceCandidateIds").add(source.getId().toString());
        return node;
    }

    private AppException invalid() {
        return new AppException(WorldSettingErrorCode.WORLD_SETTING_WORKER_JOB_INVALID);
    }
}
