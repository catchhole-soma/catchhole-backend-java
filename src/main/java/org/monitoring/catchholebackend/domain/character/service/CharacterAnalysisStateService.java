package org.monitoring.catchholebackend.domain.character.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
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

    public void prepareProvisionalCandidates(AnalysisJob job) {
        if (!job.isOrderedProvisional()) {
            return;
        }
        for (SettingCandidate candidate : candidateRepository
                .findAllByAnalysisJobIdAndProvisionalSubjectKeyIsNotNull(job.getId())) {
            if (candidate.getReviewStatus() != SettingCandidateReviewStatus.PENDING_REVIEW) {
                continue;
            }
            String key = candidate.getProvisionalSubjectKey();
            JsonNode current = runStateService.getProjectedState(job).path("characters").path(key);
            if (current.isMissingNode()) {
                SettingCandidate discovery = getCurrentDiscovery(job, key);
                java.util.Optional<JsonNode> rejected = discovery.isUserModified() ? java.util.Optional.empty()
                        : rejectionPolicy.match(discovery, runStateService.getProjectedState(job));
                if (rejected.isPresent()) {
                    preserveRejectedDiscovery(job, discovery, candidate, rejected.get());
                    continue;
                }
                ObjectNode identity = mapper.toProvisionalState(
                        discovery.getId(), discovery.getEntityName(),
                        discovery.getEpisode() == null ? null : discovery.getEpisode().getEpisodeNo()
                );
                if (discovery.getEvidenceSpans() != null) {
                    identity.set("identityEvidence", discovery.getEvidenceSpans().deepCopy());
                }
                runStateService.appendValidatedChanges(job, job.getInputStateHash(), List.of(
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
                rejectionPolicy.match(candidate, runStateService.getProjectedState(job)).ifPresent(reference -> {
                    candidate.preservePriorHumanRejection(reference);
                    runStateService.appendValidatedChanges(job, job.getInputStateHash(),
                            List.of(rejectionPolicy.referenceChange(candidate.getId(), reference)));
                });
            }
        }
    }

    private void preserveRejectedDiscovery(AnalysisJob job, SettingCandidate discovery,
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
            reference.putArray("sourceCandidateIds").add(candidate.getId().toString());
            String eventId = "character-rejected-discovery-reference:" + candidate.getId();
            changes.add(new AnalysisStateChange(eventId, List.of("references", eventId), reference, false,
                    "REVIEW_REQUIRED", List.of(candidate.getId())));
        }
        runStateService.appendValidatedChanges(job, job.getInputStateHash(), changes);
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

    public JsonNode getTarget(AnalysisJob job, UUID actualCharacterId, String provisionalSubjectKey) {
        if (!job.isOrderedProvisional() || (actualCharacterId == null) == (provisionalSubjectKey == null)) {
            throw invalidTarget();
        }
        String ref = actualCharacterId == null ? provisionalSubjectKey
                : CharacterAnalysisStateMapper.persistedRef(actualCharacterId);
        JsonNode target = runStateService.getProjectedState(job).path("characters").path(ref);
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
        if (!job.isOrderedProvisional()) {
            return;
        }
        String ref = candidate.getProvisionalSubjectKey() == null
                ? CharacterAnalysisStateMapper.persistedRef(candidate.getMatchedCharacterId())
                : candidate.getProvisionalSubjectKey();
        CharacterFactOperation operation = Objects.requireNonNull(candidate.getSuggestedOperation());
        JsonNode priorTarget = getTarget(job, candidate.getMatchedCharacterId(), candidate.getProvisionalSubjectKey());
        String eventPrefix = "character-decision:" + candidate.getId();
        List<AnalysisStateChange> changes = new ArrayList<>();
        List<UUID> sources = List.of(candidate.getId());
        LinkedHashSet<UUID> provenanceIds = new LinkedHashSet<>(dependencies);
        provenanceIds.add(candidate.getId());
        Integer episodeNo = candidate.getEpisode() == null ? null : candidate.getEpisode().getEpisodeNo();
        boolean applied = operation == CharacterFactOperation.ADD || operation == CharacterFactOperation.UPDATE
                || operation == CharacterFactOperation.MERGE || operation == CharacterFactOperation.REMOVE;
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
                        mapper.toSlot(proposed, "PROVISIONAL", episodeNo, List.copyOf(provenanceIds)),
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
            reference.putArray("sourceCandidateIds").add(candidate.getId().toString());
            changes.add(new AnalysisStateChange(eventPrefix + ":reference",
                    List.of("references", eventPrefix), reference, false, operation.name(), sources));
        }
        runStateService.appendValidatedChanges(job, job.getInputStateHash(), changes);
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
