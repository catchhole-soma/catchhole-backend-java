package org.monitoring.catchholebackend.domain.character.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJournalContributor;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisHumanRejectionPolicy;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;

/** 누적 실행의 상태를 실제 캐릭터 Entity로 위장하지 않고 읽고 기록한다. */
@Component
@RequiredArgsConstructor
public class CharacterAnalysisStateService implements AnalysisJournalContributor {

    private final AnalysisRunStateService runStateService;
    private final SettingCandidateRepository candidateRepository;
    private final CharacterAnalysisStateMapper mapper;
    private final AnalysisHumanRejectionPolicy rejectionPolicy;
    private final org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver supportingEvidence;

    public ObjectNode captureSupportingOrigin(SettingCandidate candidate) {
        return supportingEvidence.captureCandidate(supportingEvidence.begin(candidate.getWork()), candidate);
    }

    public void recordNarrativeTimeSelection(SettingCandidate candidate, boolean advance, JsonNode current) {
        JsonNode verified = advance ? supportingEvidence.captureNarrativeTime(supportingEvidence.begin(candidate.getWork()), candidate, candidate.getNarrativeTime()) : null;
        candidate.recordNarrativeTimeSelection(org.monitoring.catchholebackend.domain.character.processor.CharacterNarrativeTime.selection(
                candidate, advance && candidate.getAutomaticReviewHoldReason() == null, current, verified));
    }

    public void prepareProvisionalCandidates(AnalysisJob job) {
        if (!job.isOrderedProvisional()) {
            return;
        }
        var prepared = runStateService.prepareState(job);
        var matching = rejectionPolicy.beginMatching();
        Map<String, SettingCandidate> discoveries = new HashMap<>();
        for (SettingCandidate candidate : candidateRepository
                .findAllByAnalysisJobIdAndProvisionalSubjectKeyIsNotNull(job.getId())) {
            if (candidate.getReviewStatus() != SettingCandidateReviewStatus.PENDING_REVIEW) {
                continue;
            }
            String key = candidate.getProvisionalSubjectKey();
            JsonNode current = prepared.projectedState().path("characters").path(key);
            if (current.isMissingNode()) {
                SettingCandidate discovery = discoveries.computeIfAbsent(key, ref -> getCurrentDiscovery(job, ref));
                java.util.Optional<JsonNode> rejected = discovery.isUserModified() ? java.util.Optional.empty()
                        : matching.match(discovery, prepared.projectedState());
                if (rejected.isPresent()) {
                    preserveRejectedDiscovery(job, prepared, discovery, candidate, rejected.get());
                    continue;
                }
                ObjectNode identity = mapper.toProvisionalState(
                        discovery.getId(), discovery.getEntityName(),
                        discovery.getEpisode() == null ? null : discovery.getEpisode().getEpisodeNo()
                );
                org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisSourceMetadata.put((ObjectNode) identity.path("provenance"), discovery.getAnalysisJob());
                if (discovery.getEvidenceSpans() != null) {
                    identity.set("identityEvidence", discovery.getEvidenceSpans().deepCopy());
                }
                prepared.append(List.of(
                        new AnalysisStateChange("character-discovery:" + discovery.getId(),
                                List.of("characters", key), identity, false, "IDENTITY",
                                List.of(discovery.getId()))
                ));
            } else if (!key.equals(current.path("provisionalSubjectKey").asText())
                    || !current.path("actualCharacterId").isNull()) {
                throw invalidTarget();
            }
            if (!candidate.isCharacterDiscovery()) {
                candidate.prepareProvisionalComparison();
            }
        }
        for (SettingCandidate candidate : candidateRepository.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(job.getId())) {
            if (!candidate.isCharacterDiscovery() && !candidate.isUserModified()
                    && candidate.getReviewStatus() == SettingCandidateReviewStatus.PENDING_REVIEW
                    && candidate.getComparisonStatus() == CharacterFactComparisonStatus.PENDING) {
                matching.match(candidate, prepared.projectedState()).ifPresent(reference -> {
                    candidate.preservePriorHumanRejection(reference);
                    prepared.append(
                            List.of(rejectionPolicy.referenceChange(candidate.getId(), reference)));
                });
            }
        }
    }

    private void preserveRejectedDiscovery(AnalysisJob job, AnalysisRunStateService.PreparedState prepared, SettingCandidate discovery,
            SettingCandidate candidate, JsonNode rejection) {
        discovery.holdForRejectedDiscoveryAnchor(rejection);
        List<AnalysisStateChange> changes = new ArrayList<>();
        changes.add(rejectionPolicy.referenceChange(discovery.getId(), rejection));
        if (!candidate.isCharacterDiscovery()) {
            candidate.holdForRejectedDiscoveryAnchor(rejection);
            ObjectNode reference = JsonNodeFactory.instance.objectNode();
            reference.put("domain", domain());
            reference.put("operation", "REVIEW_REQUIRED");
            reference.put("reason", "CHARACTER_DISCOVERY_USER_REJECTED");
            reference.put("candidateId", candidate.getId().toString());
            reference.put("targetRef", candidate.getProvisionalSubjectKey());
            reference.put("discoveryCandidateId", discovery.getId().toString());
            reference.put("rejectedCandidateId", rejection.path("rejectedCandidateId").asText());
            reference.put("sourceEpisodeNo", job.getSourceEpisodeNo());
            org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisSourceMetadata.put(reference, job);
            reference.putArray("sourceCandidateIds").add(candidate.getId().toString());
            String eventId = "character-rejected-discovery-reference:" + candidate.getId();
            changes.add(new AnalysisStateChange(eventId, List.of("references", eventId), reference, false,
                    "REVIEW_REQUIRED", List.of(candidate.getId())));
        }
        prepared.append(changes);
    }

    @Override
    public String domain() {
        return "characters";
    }

    @Override
    public void finalizeChanges(AnalysisJob job) {
        if (!job.isOrderedProvisional()) {
            return;
        }
        prepareProvisionalCandidates(job);
        java.util.Set<String> covered = new java.util.HashSet<>();
        java.util.Set<String> recordedEvents = new java.util.HashSet<>();
        job.getStateJournal().path("changes").forEach(change -> recordedEvents.add(change.path("eventId").asText()));
        job.getStateJournal().path("changes").forEach(change -> change.path("sourceCandidateIds")
                .forEach(id -> covered.add(id.asText())));
        List<AnalysisStateChange> references = new ArrayList<>();
        for (SettingCandidate candidate : candidateRepository.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(job.getId())) {
            boolean unresolved = candidate.getComparisonStatus()
                    == org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus.WAITING_FOR_CHARACTER_MATCH;
            boolean failedComparison = job.isAutomaticReview()
                    && candidate.canDeferFailedComparison();
            if (failedComparison && candidate.getPreparationFailureStage()
                    == org.monitoring.catchholebackend.domain.analysis.type.CandidatePreparationFailureStage.SUBJECT_RESOLUTION) {
                candidate.recordAutomaticReviewHold(
                        org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.SUBJECT_RESOLUTION_FAILED);
            }
            String eventId = (failedComparison ? "character-comparison-failed:" : "character-unresolved:")
                    + candidate.getId();
            if (recordedEvents.contains(eventId) || !failedComparison && covered.contains(candidate.getId().toString())) {
                continue;
            }
            if (!candidate.isCharacterDiscovery() && !unresolved && !failedComparison) {
                // 미완료와 실행 자체의 중단은 참고로 대체하지 않는다. core coverage가 봉인을 막는다.
                continue;
            }
            ObjectNode reference = JsonNodeFactory.instance.objectNode();
            reference.put("domain", domain());
            reference.put("operation", candidate.isCharacterDiscovery() ? "CHARACTER_DISCOVERY" : "REVIEW_REQUIRED");
            reference.put("reason", failedComparison
                    ? candidate.getAutomaticReviewHoldReason() == null
                        ? "설정 비교를 완료하지 못해 직접 확인이 필요한 참고 정보입니다."
                        : candidate.getAutomaticReviewHoldReason().getMessage()
                    : "CHARACTER_MATCH_UNRESOLVED");
            reference.put("candidateId", candidate.getId().toString());
            reference.put("subjectName", candidate.getEntityName());
            reference.put("factKey", candidate.getAttributeName());
            reference.put("factValue", candidate.getAttributeValue());
            reference.set("valueJson", candidate.getValueJson() == null ? null : candidate.getValueJson().deepCopy());
            reference.put("sourceEpisodeNo", candidate.getEpisode() == null ? null : candidate.getEpisode().getEpisodeNo());
            org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisSourceMetadata.put(reference, candidate.getAnalysisJob());
            reference.putArray("sourceCandidateIds").add(candidate.getId().toString());
            if (failedComparison) {
                reference.put("confirmationStatus", "UNCONFIRMED");
                if (candidate.getEvidenceSpans() != null) {
                    reference.set("evidenceSpans", candidate.getEvidenceSpans().deepCopy());
                }
            }
            references.add(new AnalysisStateChange(eventId, List.of("references", eventId), reference, false,
                    reference.path("operation").asText(), List.of(candidate.getId())));
        }
        runStateService.appendValidatedChanges(job, job.getInputStateHash(), references);
    }

    public AnalysisRunStateService.PreparedState prepareState(AnalysisJob job) {
        return runStateService.prepareState(job);
    }

    public JsonNode getTarget(AnalysisJob job, UUID actualCharacterId, String provisionalSubjectKey) {
        return getTarget(job, actualCharacterId, provisionalSubjectKey, null);
    }

    public JsonNode getTarget(AnalysisJob job, UUID actualCharacterId, String provisionalSubjectKey,
            AnalysisRunStateService.PreparedState prepared) {
        if (!job.isOrderedProvisional() || (actualCharacterId == null) == (provisionalSubjectKey == null)) {
            throw invalidTarget();
        }
        String ref = actualCharacterId == null ? provisionalSubjectKey
                : CharacterAnalysisStateMapper.persistedRef(actualCharacterId);
        JsonNode target = (prepared == null ? runStateService.getProjectedState(job) : prepared.projectedState())
                .path("characters").path(ref);
        if (!target.isObject() || !target.path("slots").isObject()
                || !target.path("name").isTextual()
                || actualCharacterId != null
                && !actualCharacterId.toString().equals(target.path("actualCharacterId").asText())
                || provisionalSubjectKey != null
                && !provisionalSubjectKey.equals(target.path("provisionalSubjectKey").asText())) {
            throw invalidTarget();
        }
        return target.deepCopy();
    }

    public void recordDecision(
            AnalysisJob job, SettingCandidate candidate, CharacterSnapshotEntry proposed,
            List<CharacterSnapshotSlot> removals, List<UUID> dependencies
    ) {
        recordDecision(job, candidate, proposed, removals, dependencies, null);
    }

    public void recordDecision(
            AnalysisJob job, SettingCandidate candidate, CharacterSnapshotEntry proposed,
            List<CharacterSnapshotSlot> removals, List<UUID> dependencies, String incompatibleSnapshotRef
    ) {
        recordDecision(job, candidate, proposed, removals, dependencies, incompatibleSnapshotRef, null);
    }

    public void recordDecision(
            AnalysisJob job, SettingCandidate candidate, CharacterSnapshotEntry proposed,
            List<CharacterSnapshotSlot> removals, List<UUID> dependencies, String incompatibleSnapshotRef,
            AnalysisRunStateService.PreparedState prepared
    ) {
        if (!job.isOrderedProvisional()) {
            return;
        }
        String ref = candidate.getProvisionalSubjectKey() == null
                ? CharacterAnalysisStateMapper.persistedRef(candidate.getMatchedCharacterId())
                : candidate.getProvisionalSubjectKey();
        CharacterFactOperation operation = Objects.requireNonNull(candidate.getSuggestedOperation());
        JsonNode priorTarget = getTarget(job, candidate.getMatchedCharacterId(), candidate.getProvisionalSubjectKey(), prepared);
        String eventPrefix = "character-decision:" + candidate.getId();
        List<AnalysisStateChange> changes = new ArrayList<>();
        List<UUID> sources = List.of(candidate.getId());
        LinkedHashSet<UUID> provenanceIds = new LinkedHashSet<>(dependencies);
        provenanceIds.add(candidate.getId());
        Integer episodeNo = candidate.getEpisode() == null ? null : candidate.getEpisode().getEpisodeNo();
        boolean applied = operation == CharacterFactOperation.ADD || operation == CharacterFactOperation.UPDATE
                || operation == CharacterFactOperation.MERGE || operation == CharacterFactOperation.REMOVE;
        var hold = !candidate.isAutomaticPastHistoryOnly()
                && org.monitoring.catchholebackend.domain.analysis.processor.SettingBookTemporalPolicy.requiresReview(
                org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.from(
                        candidate.getAnalysisJob(), candidate.getEffectiveFromEpisodeNo(), candidate.getTemporalScope()), List.of(), false)
                ? org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.SETTING_TIME_CONFIRMATION_REQUIRED
                : applied ? projectedHold(candidate, proposed, removals, priorTarget, prepared) : null;
        if (hold != null) {
            candidate.recordAutomaticReviewHold(hold);
            applied = false;
        }
        if (job.isAutomaticReview() && operation == CharacterFactOperation.REVIEW_REQUIRED && incompatibleSnapshotRef != null
                && hold == null && candidate.getTemporalScope() == org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope.PRESENT) {
            hold = org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.REVIEW_REQUIRED;
            candidate.recordAutomaticReviewHold(hold);
            List<UUID> heldIds = new ArrayList<>(); heldIds.add(candidate.getId());
            var affectedSlots = new LinkedHashSet<String>();
            String destinationKey = CharacterAnalysisStateMapper.slotKey(proposed.slot());
            affectedSlots.add(destinationKey);
            for (UUID id : dependencies) {
                SettingCandidate prior = candidateRepository.findByIdAndWorkIdForUpdate(id, job.getWork().getId()).orElseThrow(this::invalidTarget);
                if (prior.getAnalysisJob() != null && prior.getAnalysisJob().getId().equals(job.getId())
                        && Objects.equals(prior.getMatchedCharacterId(), candidate.getMatchedCharacterId())
                        && Objects.equals(prior.getProvisionalSubjectKey(), candidate.getProvisionalSubjectKey())
                        && prior.getCharacterComparisonBatch() != null
                        && comparisonFactType(prior) == proposed.slot().factType()
                        && Objects.equals(prior.getResolvedCanonicalFactKey(), proposed.slot().factKey())) {
                    prior.recordAutomaticReviewHold(hold); heldIds.add(id);
                    if (prior.getRemovedSnapshotEntriesJson() != null) prior.getRemovedSnapshotEntriesJson().forEach(removed ->
                            affectedSlots.add(CharacterAnalysisStateMapper.slotKey(new CharacterSnapshotSlot(
                                    org.monitoring.catchholebackend.domain.character.type.CharacterFactType.valueOf(removed.path("factType").asText()), removed.path("factKey").asText()))));
                }
            }
            JsonNode baseline = runStateService.getInputState(job).path("characters").path(ref);
            ObjectNode restoredNarrative = narrativeWithoutHeldContributions(job, ref, baseline, heldIds, prepared);
            JsonNode restoredTime = restoredNarrative.path("currentNarrativeTime");
            if (!Objects.equals(org.monitoring.catchholebackend.domain.character.processor.CharacterNarrativeTime.value(priorTarget.path("currentNarrativeTime")),
                    org.monitoring.catchholebackend.domain.character.processor.CharacterNarrativeTime.value(restoredTime))) {
                changes.add(new AnalysisStateChange(eventPrefix + ":restore-narrative-time", List.of("characters", ref, "currentNarrativeTime"),
                        restoredTime.isNull() ? null : restoredTime, restoredTime.isNull(), "REVIEW_REQUIRED", heldIds));
            }
            changes.add(new AnalysisStateChange(eventPrefix + ":restore-history", List.of("characters", ref, "historyFacts"),
                    restoredNarrative.path("historyFacts"), false, "REVIEW_REQUIRED", heldIds));
            for (String slotKey : affectedSlots) {
                String suffix = slotKey.equals(destinationKey) ? "" : ":" + slotKey;
                JsonNode original = baseline.path("slots").path(slotKey), absence = baseline.path("absences").path(slotKey);
                if (!original.isMissingNode() || priorTarget.path("slots").has(slotKey)) changes.add(new AnalysisStateChange(eventPrefix + ":restore-slot" + suffix, List.of("characters", ref, "slots", slotKey),
                        original.isMissingNode() ? null : original.deepCopy(), original.isMissingNode(), "REVIEW_REQUIRED", heldIds));
                if (!absence.isMissingNode() || priorTarget.path("absences").has(slotKey)) changes.add(new AnalysisStateChange(eventPrefix + ":restore-absence" + suffix, List.of("characters", ref, "absences", slotKey),
                        absence.isMissingNode() ? null : absence.deepCopy(), absence.isMissingNode(), "REVIEW_REQUIRED", heldIds));
            }
        }
        if (applied) {
            for (CharacterSnapshotSlot removed : removals) {
                String slotKey = CharacterAnalysisStateMapper.slotKey(removed);
                changes.add(new AnalysisStateChange(eventPrefix + ":remove:" + slotKey,
                        List.of("characters", ref, "slots", slotKey), null, true, operation.name(), sources));
                ObjectNode absence = JsonNodeFactory.instance.objectNode();
                absence.putArray("sourceCandidateIds").addAll(provenanceIds.stream()
                        .map(id -> JsonNodeFactory.instance.textNode(id.toString())).toList());
                changes.add(new AnalysisStateChange(eventPrefix + ":absence:" + slotKey,
                        List.of("characters", ref, "absences", slotKey), absence, false,
                        operation.name(), sources));
            }
            if (operation != CharacterFactOperation.REMOVE) {
                String slotKey = CharacterAnalysisStateMapper.slotKey(proposed.slot());
                changes.add(new AnalysisStateChange(eventPrefix + ":upsert",
                        List.of("characters", ref, "slots", slotKey),
                        projectedSlot(candidate, proposed, episodeNo, List.copyOf(provenanceIds), priorTarget),
                        false, operation.name(), sources));
                if (priorTarget.path("absences").has(slotKey)) {
                    changes.add(new AnalysisStateChange(eventPrefix + ":clear-absence",
                            List.of("characters", ref, "absences", slotKey), null, true,
                            operation.name(), sources));
                }
            }
        } else {
            ObjectNode reference = JsonNodeFactory.instance.objectNode();
            reference.put("domain", "characters");
            reference.put("targetRef", ref);
            reference.put("operation", operation.name());
            reference.put("reason", candidate.getComparisonReason());
            reference.put("sourceEpisodeNo", episodeNo);
            reference.put("candidateId", candidate.getId().toString());
            reference.put("factType", proposed.slot().factType().name());
            reference.put("factKey", proposed.slot().factKey());
            reference.put("factValue", candidate.getAttributeValue());
            reference.set("valueJson", candidate.getValueJson() == null ? null : candidate.getValueJson().deepCopy());
            reference.put("proposedFactValue", candidate.getProposedFactValue());
            reference.set("proposedValueJson", candidate.getProposedValueJson() == null
                    ? null : candidate.getProposedValueJson().deepCopy());
            reference.put("temporalScope", candidate.getTemporalScope() == null
                    ? null : candidate.getTemporalScope().name());
            reference.put("effectiveFromEpisodeNo", candidate.getEffectiveFromEpisodeNo());
            if (incompatibleSnapshotRef != null) reference.put("targetSnapshotRef", incompatibleSnapshotRef);
            if (hold != null) reference.put("automaticReviewHoldReason", hold.name());
            reference.putArray("sourceCandidateIds").addAll(provenanceIds.stream()
                    .map(id -> JsonNodeFactory.instance.textNode(id.toString())).toList());
            changes.add(new AnalysisStateChange(eventPrefix + ":reference",
                    List.of("references", eventPrefix), reference, false, operation.name(), sources));
        }
        if (hold == null) {
            JsonNode selected = org.monitoring.catchholebackend.domain.character.processor.CharacterNarrativeTime.selected(candidate, priorTarget.path("currentNarrativeTime"));
            if (selected != null) {
                ObjectNode frozen = (ObjectNode) selected;
                frozen.set("supportingSource", supportingEvidence.captureCandidate(supportingEvidence.begin(candidate.getWork()), candidate));
                changes.add(new AnalysisStateChange(eventPrefix + ":narrative-time", List.of("characters", ref, "currentNarrativeTime"),
                        frozen, false, operation.name(), sources));
                changes.add(new AnalysisStateChange(eventPrefix + ":narrative-version", List.of("characters", ref, "snapshotVersion"),
                        JsonNodeFactory.instance.numberNode(priorTarget.path("snapshotVersion").asLong() + 1), false, operation.name(), sources));
            }
        }
        if (hold == null && (applied || operation == CharacterFactOperation.HISTORY_ONLY)) {
            var history = priorTarget.path("historyFacts").isArray()
                    ? (com.fasterxml.jackson.databind.node.ArrayNode) priorTarget.path("historyFacts").deepCopy() : JsonNodeFactory.instance.arrayNode();
            var ended = new LinkedHashSet<CharacterSnapshotSlot>(removals);
            if (applied && operation != CharacterFactOperation.MERGE && operation != CharacterFactOperation.REMOVE) ended.add(proposed.slot());
            for (var slot : ended) {
                JsonNode old = priorTarget.path("slots").path(CharacterAnalysisStateMapper.slotKey(slot));
                for (JsonNode origin : old.path("supportingSources")) history.add(historyFact(null, origin.path("sourceCandidateId"),
                        slot, old.path("factValue"), old.path("valueJson"), null, null, JsonNodeFactory.instance.arrayNode(), origin));
            }
            if (operation == CharacterFactOperation.REMOVE || operation == CharacterFactOperation.HISTORY_ONLY) {
                ObjectNode origin = supportingEvidence.captureCandidate(supportingEvidence.begin(candidate.getWork()), candidate);
                history.add(historyFact(null, JsonNodeFactory.instance.textNode(candidate.getId().toString()), proposed.slot(),
                        JsonNodeFactory.instance.textNode(candidate.getAttributeValue() == null ? "" : candidate.getAttributeValue()),
                        candidate.getValueJson(), operation.name(), candidate.getTemporalScope() == null ? null : candidate.getTemporalScope().name(),
                        candidate.getRemovedSnapshotEntriesJson(), origin));
            }
            changes.add(new AnalysisStateChange(eventPrefix + ":history", List.of("characters", ref, "historyFacts"), history,
                    false, operation.name(), sources));
        }
        if (prepared == null) runStateService.appendValidatedChanges(job, job.getInputStateHash(), changes);
        else prepared.append(changes);
    }

    private org.monitoring.catchholebackend.domain.character.type.CharacterFactType comparisonFactType(SettingCandidate candidate) {
        var legacyType = candidate.getCharacterComparisonBatch().getCanonicalFactType();
        if (legacyType != null) return legacyType;
        // Every projected upsert stores its validated slot type; REMOVE is restricted to STATUS.
        return candidate.getSuggestedOperation() == CharacterFactOperation.REMOVE
                ? org.monitoring.catchholebackend.domain.character.type.CharacterFactType.STATUS
                : candidate.getComparisonTargetFactType();
    }

    /** Rebuild only temporal observations and history; unrelated slot restoration keeps its existing policy. */
    private ObjectNode narrativeWithoutHeldContributions(AnalysisJob job, String ref, JsonNode baseline, List<UUID> newlyHeld,
            AnalysisRunStateService.PreparedState prepared) {
        var excluded = new java.util.HashSet<String>();
        newlyHeld.forEach(id -> excluded.add(id.toString()));
        for (JsonNode reference : (prepared == null ? runStateService.getProjectedState(job) : prepared.projectedState()).path("references")) {
            if (ref.equals(reference.path("targetRef").asText()) && reference.hasNonNull("automaticReviewHoldReason")) {
                reference.path("sourceCandidateIds").forEach(id -> excluded.add(id.asText()));
            }
        }
        JsonNode time = baseline.path("currentNarrativeTime").isMissingNode()
                ? JsonNodeFactory.instance.nullNode() : baseline.path("currentNarrativeTime").deepCopy();
        List<JsonNode> history = new ArrayList<>();
        baseline.path("historyFacts").forEach(row -> history.add(row.deepCopy()));
        List<JsonNode> originalHistory = new ArrayList<>(history);
        JsonNode journal = job.getStateJournal();
        if (journal != null) for (JsonNode change : journal.path("changes")) {
            JsonNode path = change.path("path");
            if (path.size() != 3 || !"characters".equals(path.path(0).asText()) || !ref.equals(path.path(1).asText())) continue;
            boolean held = false;
            for (JsonNode id : change.path("sourceCandidateIds")) if (excluded.contains(id.asText())) held = true;
            String field = path.path(2).asText(), eventId = change.path("eventId").asText();
            JsonNode recorded = change.path("value");
            if ("currentNarrativeTime".equals(field) && !held && !eventId.endsWith(":restore-narrative-time")
                    && recorded.path("sourceCandidateId").isTextual()) {
                UUID id = UUID.fromString(recorded.path("sourceCandidateId").asText());
                SettingCandidate source = candidateRepository.findByIdAndWorkIdForUpdate(id, job.getWork().getId()).orElseThrow(this::invalidTarget);
                JsonNode selected = org.monitoring.catchholebackend.domain.character.processor.CharacterNarrativeTime.selected(source, time);
                if (selected != null && selected.equals(org.monitoring.catchholebackend.domain.character.processor.CharacterNarrativeTime.value(recorded))) time = recorded.deepCopy();
            }
            if ("historyFacts".equals(field) && recorded.isArray()) {
                List<JsonNode> incoming = new ArrayList<>(); recorded.forEach(incoming::add);
                // Stored arrays are cumulative. Replay each contribution's delta so a later valid array cannot resurrect held additions.
                List<JsonNode> additions = new ArrayList<>(incoming), removals = new ArrayList<>(originalHistory);
                for (JsonNode old : originalHistory) additions.remove(old);
                for (JsonNode next : incoming) removals.remove(next);
                if (!held && !eventId.endsWith(":restore-history")) {
                    removals.forEach(history::remove);
                    additions.forEach(row -> history.add(row.deepCopy()));
                }
                originalHistory = incoming;
            }
        }
        ObjectNode restored = JsonNodeFactory.instance.objectNode();
        restored.set("currentNarrativeTime", time);
        var rows = restored.putArray("historyFacts"); history.forEach(rows::add);
        return restored;
    }

    private ObjectNode historyFact(UUID factId, JsonNode candidateId, CharacterSnapshotSlot slot, JsonNode value, JsonNode valueJson,
            String operation, String scope, JsonNode removals, JsonNode origin) {
        ObjectNode row = JsonNodeFactory.instance.objectNode().put("factId", factId == null ? null : factId.toString());
        row.set("sourceCandidateId", candidateId.isMissingNode() ? JsonNodeFactory.instance.nullNode() : candidateId.deepCopy());
        row.put("factType", slot.factType().name()).put("factKey", slot.factKey());
        row.set("factValue", value == null ? JsonNodeFactory.instance.nullNode() : value.deepCopy());
        row.set("valueJson", valueJson == null ? JsonNodeFactory.instance.nullNode() : valueJson.deepCopy());
        row.put("operation", operation).put("temporalScope", scope);
        row.set("removedSnapshotEntries", removals == null ? JsonNodeFactory.instance.arrayNode() : removals.deepCopy());
        row.set("narrativeTime", origin.path("narrativeTime").isMissingNode() ? JsonNodeFactory.instance.nullNode() : origin.path("narrativeTime").deepCopy());
        row.set("supportingSource", origin.deepCopy());
        return row;
    }

    private ObjectNode projectedSlot(SettingCandidate candidate, CharacterSnapshotEntry proposed, Integer episodeNo,
            List<UUID> ids, JsonNode priorTarget) {
        ObjectNode slot = mapper.toSlot(proposed, "PROVISIONAL", episodeNo, ids, candidate.getAnalysisJob());
        ObjectNode provenance = (ObjectNode) slot.path("provenance");
        provenance.put("effectiveFromEpisodeNo", candidate.getEffectiveFromEpisodeNo());
        provenance.put("currentValueProtected", false);
        var times = provenance.putArray("currentSourceTimes");
        if (candidate.getSuggestedOperation() == CharacterFactOperation.MERGE) {
            var old = priorTarget.path("slots").path(CharacterAnalysisStateMapper.slotKey(proposed.slot())).path("provenance").path("currentSourceTimes");
            if (old.isArray()) old.forEach(time -> times.add(time.deepCopy()));
        }
        times.add(org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.toJson(
                org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.from(candidate.getAnalysisJob(), candidate.getEffectiveFromEpisodeNo(), candidate.getTemporalScope())));
        var origins = slot.putArray("supportingSources");
        var capture = supportingEvidence.begin(candidate.getWork());
        for (UUID id : ids) {
            JsonNode frozen = null;
            for (JsonNode priorSlot : priorTarget.path("slots")) for (JsonNode origin : priorSlot.path("supportingSources")) {
                if (id.toString().equals(origin.path("sourceCandidateId").asText())) frozen = origin;
            }
            if (frozen != null) origins.add(frozen.deepCopy());
            else {
                SettingCandidate source = id.equals(candidate.getId()) ? candidate : candidateRepository.findByIdAndWorkId(id, candidate.getWork().getId()).orElseThrow(this::invalidTarget);
                origins.add(supportingEvidence.captureCandidate(capture, source));
            }
        }
        return slot;
    }

    private org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason projectedHold(
            SettingCandidate candidate, CharacterSnapshotEntry proposed, List<CharacterSnapshotSlot> removals, JsonNode target,
            AnalysisRunStateService.PreparedState prepared) {
        var incoming = org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.from(
                candidate.getAnalysisJob(), candidate.getEffectiveFromEpisodeNo(), candidate.getTemporalScope());
        if (org.monitoring.catchholebackend.domain.analysis.processor.SettingBookTemporalPolicy.requiresReview(incoming, List.of(), false)) {
            return org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.SETTING_TIME_CONFIRMATION_REQUIRED;
        }
        if (candidate.getAnalysisJob().isAutomaticReview()) {
            String ref = candidate.getProvisionalSubjectKey() == null
                    ? CharacterAnalysisStateMapper.persistedRef(candidate.getMatchedCharacterId()) : candidate.getProvisionalSubjectKey();
            for (JsonNode reference : (prepared == null ? runStateService.getProjectedState(candidate.getAnalysisJob()) : prepared.projectedState()).path("references")) {
                if ("characters".equals(reference.path("domain").asText()) && ref.equals(reference.path("targetRef").asText())
                        && proposed.slot().factType().name().equals(reference.path("factType").asText())
                        && proposed.slot().factKey().equals(reference.path("factKey").asText())
                        && reference.hasNonNull("targetSnapshotRef")
                        && "REVIEW_REQUIRED".equals(reference.path("automaticReviewHoldReason").asText())) {
                    return org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.DEPENDENCY_CONFIRMATION_REQUIRED;
                }
            }
        }
        var paths = new LinkedHashSet<CharacterSnapshotSlot>(removals);
        paths.add(proposed.slot());
        if (candidate.getComparisonTargetFactType() != null && candidate.getComparisonTargetFactKey() != null) {
            paths.add(new CharacterSnapshotSlot(candidate.getComparisonTargetFactType(), candidate.getComparisonTargetFactKey()));
        }
        for (var path : paths) {
            JsonNode current = target.path("slots").path(CharacterAnalysisStateMapper.slotKey(path));
            boolean changes = removals.contains(path) || candidate.getSuggestedOperation() == CharacterFactOperation.REMOVE
                    || candidate.getAnalysisJob().isAutomaticReview() && candidate.getSuggestedOperation() == CharacterFactOperation.MERGE
                    || !path.equals(proposed.slot()) || !current.isObject() || !Objects.equals(current.path("valueJson"), proposed.valueJson());
            if (!changes) continue;
            boolean protectedPath = CharacterAnalysisStateMapper.protectedSlots(target).stream()
                    .anyMatch(slot -> slot.factType() == path.factType() && slot.factKey().equals(path.factKey()));
            if (candidate.getAnalysisJob().isSettingBookSource() && (protectedPath || current.path("provenance").path("currentValueProtected").asBoolean())) {
                return org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.HUMAN_CURRENT_SETTING_PROTECTED;
            }
            if (current.isObject() && org.monitoring.catchholebackend.domain.analysis.processor.SettingBookTemporalPolicy.requiresReview(incoming,
                    org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.fromJson(current.path("provenance").path("currentSourceTimes")),
                    !candidate.getAnalysisJob().isAutomaticReview() || removals.contains(path) || !path.equals(proposed.slot())
                            || candidate.getSuggestedOperation() != CharacterFactOperation.MERGE,
                    candidate.getAnalysisJob().isAutomaticReview())) {
                return org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.SETTING_TIME_CONFIRMATION_REQUIRED;
            }
        }
        return null;
    }

    private SettingCandidate getCurrentDiscovery(AnalysisJob job, String key) {
        UUID discoveryId;
        try {
            if (!key.startsWith("provisional-character:")) {
                throw invalidTarget();
            }
            discoveryId = UUID.fromString(key.substring("provisional-character:".length()));
        } catch (IllegalArgumentException exception) {
            throw invalidTarget();
        }
        SettingCandidate discovery = candidateRepository.findByIdAndWorkId(discoveryId, job.getWork().getId())
                .orElseThrow(this::invalidTarget);
        if (!discovery.isCharacterDiscovery() || discovery.getAnalysisJob() == null
                || !discovery.getAnalysisJob().getId().equals(job.getId())
                || discovery.getReviewStatus() != SettingCandidateReviewStatus.PENDING_REVIEW) {
            throw invalidTarget();
        }
        return discovery;
    }

    private AppException invalidTarget() {
        return new AppException(CharacterErrorCode.SETTING_CANDIDATE_WORKER_JOB_INVALID);
    }
}
