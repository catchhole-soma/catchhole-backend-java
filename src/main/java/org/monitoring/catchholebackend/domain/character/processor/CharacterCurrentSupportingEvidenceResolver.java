package org.monitoring.catchholebackend.domain.character.processor;

import org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureDiagnostics;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobSourceView;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobSourceRepository;
import org.monitoring.catchholebackend.domain.analysis.processor.GeneralEvidenceProjection;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.springframework.stereotype.Component;

/** Reuses source-bound verification recorded at candidate storage; comparison capture never reads S3. */
@Component
@RequiredArgsConstructor
public class CharacterCurrentSupportingEvidenceResolver {
    private final org.monitoring.catchholebackend.domain.character.repository.StoredCharacterEvidenceRepository storedEvidence;

    private final AnalysisJobSourceRepository sourceJobs;

    public Capture begin(Work work) {
        Capture capture = new Capture(work.getId());
        sourceJobs.findExtractionSourcesForWork(work.getId()).forEach(source -> capture.sources.put(source.getId(), source));
        return capture;
    }

    /** #387 대상별 읽기용. 작품 전체 출처를 미리 읽지 않고 필요한 Job만 {@link #source}에서 읽는다. 결과는 begin과 같다. */
    public Capture beginLazily(Work work) {
        return new Capture(work.getId());
    }

    /** The proxy identifier is safe to read without hydrating its large Job JSON columns. */
    public AnalysisJobSourceView source(Capture capture, AnalysisJob job) {
        if (job == null) return null;
        return capture.sources.computeIfAbsent(job.getId(), id -> sourceJobs.findSourceById(id).orElseThrow(this::invalid));
    }

    public ArrayNode captureSlot(Capture capture, WorkCharacter owner, CharacterSnapshotSlot slot,
            List<CharacterFact> facts) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        if (facts.isEmpty()) {
            result.add(unavailable(null, null, "LEGACY_NO_MANIFEST", null));
            return result;
        }
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (CharacterFact fact : facts) {
            if (!same(owner.getWork().getId(), capture.workId)
                    || fact.getWorkCharacter() == null || !same(owner.getId(), fact.getWorkCharacter().getId())
                    || fact.getFactType() != slot.factType() || !Objects.equals(fact.getFactKey(), slot.factKey())) throw invalid();
            SettingCandidate candidate = fact.getSettingCandidate();
            if (candidate == null) {
                result.add(unavailable(null, null, "HUMAN_CURRENT_WITHOUT_SOURCE", null));
                continue;
            }
            if (candidate.getId() == null || !seen.add(candidate.getId())) throw invalid();
            AnalysisJob job = fact.getExtractedByJob();
            if (!same(candidate.getWork().getId(), capture.workId)
                    || !same(candidate.getMatchedCharacterId(), owner.getId())) throw invalid();
            if (candidate.getAnalysisJob() == null && job == null) {
                result.add(unavailable(candidate.getId(), null, "LEGACY_NO_MANIFEST", candidate.hasUserEditedContent()));
                continue;
            }
            if (candidate.getAnalysisJob() == null || job == null
                    || !same(candidate.getAnalysisJob().getId(), job.getId())) throw invalid();
            ObjectNode origin = captureRetainedCandidate(capture, candidate);
            origin.set("narrativeTime", captureNarrativeTime(capture, candidate, fact.getNarrativeTime()));
            result.add(origin);
        }
        return result;
    }

    public ObjectNode captureCandidate(Capture capture, SettingCandidate candidate) {
        return captureCandidate(capture, candidate, candidate.getEvidenceSpans());
    }

    public ObjectNode captureRetainedCandidate(Capture capture, SettingCandidate candidate) {
        return captureCandidate(capture, candidate, candidate.getEvidenceSpans());
    }

    public ArrayNode captureRetainedIdentityEvidence(Capture capture, SettingCandidate discovery) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        ObjectNode origin = captureRetainedCandidate(capture, discovery);
        result.addAll((ArrayNode) origin.path("evidenceSpans"));
        if (discovery.isReviewedAutomatically() && discovery.getAnalysisJob() != null
                && source(capture, discovery.getAnalysisJob()).isSettingBookSource() && discovery.getRawAiResultJson() != null) {
            JsonNode proof = discovery.getRawAiResultJson().path("setting_book_identity_proof");
            for (JsonNode member : proof.path("members")) {
                JsonNode span = member.path("profileEvidence");
                if (member.path("targetRef").equals(proof.path("parentTargetRef")) && span.isObject()) {
                    var verified = evidence(capture, discovery);
                    if (verified.invalidated() || !verified.verified()) continue;
                    boolean accepted = false;
                    for (JsonNode checked : verified.identitySpans()) if (checked.equals(span)) accepted = true;
                    // Unverifiable retained profile quotes cannot block reuse of a confirmed identity.
                    // Automatic promotion still validates the full identity proof at its own boundary.
                    if (accepted) result.add(span.deepCopy());
                }
            }
        }
        return result;
    }

    private ObjectNode captureCandidate(Capture capture, SettingCandidate candidate, JsonNode evidence) {
        AnalysisJobSourceView job = source(capture, candidate.getAnalysisJob());
        if (job == null) return unavailable(candidate.getId(), null, "LEGACY_NO_MANIFEST", candidate.hasUserEditedContent());
        if (!same(job.getWorkId(), capture.workId) || !same(candidate.getWork().getId(), capture.workId)
                || job.getId() == null || job.getJobType() != AnalysisJobType.SETTING_EXTRACTION) throw invalid();
        boolean noManifest = job.getSourceContentHash() == null && job.getSourceContentS3Key() == null;
        if (noManifest) return unavailable(candidate.getId(), job.getId(), "LEGACY_NO_MANIFEST", candidate.hasUserEditedContent());
        if (job.getSourceContentHash() == null || !job.getSourceContentHash().matches("[0-9a-f]{64}")
                || job.getSourceContentS3Key() == null || job.getSourceContentS3Key().isBlank()) throw new InvalidEvidenceException("CURRENT_EVIDENCE_MANIFEST_INVALID", job.getId(), job.getEpisodeId(), job.getSourceEpisodeNo());
        var retained = evidence(capture, candidate);
        if (retained.invalidated()) {
            ObjectNode row = unavailable(candidate.getId(), job.getId(), "SOURCE_EVIDENCE_PURGED", candidate.hasUserEditedContent());
            row.set("source", source(job));
            return row;
        }
        ObjectNode source = source(job);
        if (evidence == null || evidence.isNull() || evidence.isArray() && evidence.isEmpty()) {
            ObjectNode row = unavailable(candidate.getId(), job.getId(), "NO_RECORDED_QUOTE", candidate.hasUserEditedContent());
            row.set("source", source);
            storageBinding(row, job);
            return row;
        }
        var verified = evidence(capture, candidate);
        ArrayNode spans = (ArrayNode) verified.spans().deepCopy();
        ObjectNode row = spans.isEmpty()
                ? unavailable(candidate.getId(), job.getId(), verified.invalidated() ? "SOURCE_EVIDENCE_PURGED" : verified.verified()
                        ? GeneralEvidenceProjection.unavailableReason(evidence) : "EVIDENCE_UNAVAILABLE", candidate.hasUserEditedContent())
                : base(candidate.getId(), job.getId(), candidate.hasUserEditedContent())
                        .put("availability", "VERIFIED").putNull("unavailableReason");
        row.set("source", source);
        row.set("evidenceSpans", spans);
        if (!verified.invalidated()) storageBinding(row, job);
        row.set("narrativeTime", captureNarrativeTime(capture, candidate, candidate.getNarrativeTime()));
        return row;
    }

    public JsonNode captureNarrativeTime(Capture capture, SettingCandidate candidate, JsonNode time) {
        if (time == null || time.isNull()) return JsonNodeFactory.instance.nullNode();
        for (String field : List.of("factTime", "referenceTime")) {
            if (time.path(field).isObject() && time.path(field).path("evidenceSpans").isArray()
                    && time.path(field).path("evidenceSpans").isEmpty()) return JsonNodeFactory.instance.nullNode();
        }
        AnalysisJobSourceView job = source(capture, candidate.getAnalysisJob());
        if (job == null || !same(candidate.getWork().getId(), capture.workId)
                || !same(job.getWorkId(), capture.workId) || !time.isObject() || time.size() != 2
                || !time.has("factTime") || !time.has("referenceTime")) throw invalid();
        // An explicit unknown pair contains nothing to validate and must never dereference its old source.
        if (time.get("factTime").isNull() && time.get("referenceTime").isNull()) return time.deepCopy();
        var verified = evidence(capture, candidate);
        if (verified.invalidated() || !verified.verified()) return JsonNodeFactory.instance.nullNode();
        for (String field : List.of("factTime", "referenceTime")) {
            JsonNode point = time.get(field);
            if (!point.isNull()) {
                CharacterNarrativeTime.requirePoint(point);
                for (JsonNode span : point.path("evidenceSpans")) {
                    boolean accepted = false;
                    for (JsonNode checked : verified.narrativeSpans()) if (checked.equals(span)) accepted = true;
                    if (!accepted) return JsonNodeFactory.instance.nullNode();
                }
            }
        }
        if (!verified.narrativeTimeValid()) return JsonNodeFactory.instance.nullNode();
        return time.deepCopy();
    }

    public ObjectNode captureFact(Capture capture, CharacterFact fact) {
        SettingCandidate candidate = fact.getSettingCandidate();
        if (candidate == null) return unavailable(null, null, "HUMAN_CURRENT_WITHOUT_SOURCE", null);
        if (fact.getWorkCharacter() == null || !same(fact.getWorkCharacter().getWork().getId(), capture.workId)
                || !same(fact.getWorkCharacter().getId(), candidate.getMatchedCharacterId())
                || fact.getExtractedByJob() != null && (candidate.getAnalysisJob() == null
                || !same(fact.getExtractedByJob().getId(), candidate.getAnalysisJob().getId()))) throw invalid();
        ObjectNode origin = captureRetainedCandidate(capture, candidate);
        origin.set("narrativeTime", captureNarrativeTime(capture, candidate, fact.getNarrativeTime()));
        return origin;
    }

    private org.monitoring.catchholebackend.domain.character.repository.StoredCharacterEvidenceRepository.Evidence evidence(Capture capture, SettingCandidate candidate) {
        return capture.evidence.computeIfAbsent(candidate, row -> storedEvidence.find(row, source(capture, row.getAnalysisJob())));
    }

    private ObjectNode source(AnalysisJobSourceView job) {
        ObjectNode node = JsonNodeFactory.instance.objectNode().put("sourceKind", job.getSourceKind().name());
        node.putNull("episodeId").putNull("episodeNo").putNull("settingBookId").putNull("revisionId")
                .putNull("revisionNo").put("contentHash", job.getSourceContentHash())
                .putNull("referenceTimeKind").putNull("referenceEpisodeNo");
        if (job.isSettingBookSource()) {
            if (job.getSourceSettingBookId() == null || job.getSourceSettingBookRevisionId() == null
                    || !same(job.getSourceSettingBookWorkId(), job.getWorkId())
                    || !same(job.getRevisionSettingBookId(), job.getSourceSettingBookId())
                    || !Objects.equals(job.getRevisionContentHash(), job.getSourceContentHash())
                    || !Objects.equals(job.getRevisionContentS3Key(), job.getSourceContentS3Key())
                    || !Objects.equals(job.getRevisionContentS3Version(), job.getSourceContentS3Version())) throw invalid();
            node.put("settingBookId", job.getSourceSettingBookId().toString())
                    .put("revisionId", job.getSourceSettingBookRevisionId().toString())
                    .put("revisionNo", job.getSourceSettingBookRevisionNo())
                    .put("referenceTimeKind", job.getSourceReferenceTimeKind().name())
                    .put("referenceEpisodeNo", job.getSourceReferenceEpisodeNo());
        } else {
            if (job.getEpisodeId() == null || !same(job.getEpisodeWorkId(), job.getWorkId())
                    || job.getSourceEpisodeNo() == null || job.getSourceEpisodeNo() < 1) throw invalid();
            node.put("episodeId", job.getEpisodeId().toString()).put("episodeNo", job.getSourceEpisodeNo());
        }
        return node;
    }

    private void storageBinding(ObjectNode row, AnalysisJobSourceView job) {
        row.putObject("storageBinding").put("key", job.getSourceContentS3Key()).put("versionId", job.getSourceContentS3Version());
    }

    private ObjectNode unavailable(UUID candidate, UUID job, String reason, Boolean edited) {
        ObjectNode row = base(candidate, job, edited).put("availability", "UNAVAILABLE").put("unavailableReason", reason);
        row.putNull("source").putArray("evidenceSpans");
        return row;
    }

    private ObjectNode base(UUID candidate, UUID job, Boolean edited) {
        ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("sourceCandidateId", candidate == null ? null : candidate.toString());
        row.put("sourceAnalysisJobId", job == null ? null : job.toString());
        if (edited == null) row.putNull("candidateWasEdited"); else row.put("candidateWasEdited", edited);
        row.put("quoteRelation", "SOURCE_CONTEXT_ONLY");
        row.putNull("narrativeTime");
        return row;
    }

    private boolean same(UUID left, UUID right) { return left != null && left.equals(right); }
    private InvalidEvidenceException invalid() { return new InvalidEvidenceException(); }

    public static final class Capture {
        private final UUID workId;
        private final Map<UUID, AnalysisJobSourceView> sources = new java.util.HashMap<>();
        private final Map<SettingCandidate, org.monitoring.catchholebackend.domain.character.repository.StoredCharacterEvidenceRepository.Evidence> evidence = new java.util.IdentityHashMap<>();
        private Capture(UUID workId) { this.workId = Objects.requireNonNull(workId); }
    }

    /** Dedicated deterministic shape/binding failure; excludes transient source storage failures. */
    public static final class InvalidEvidenceException extends AppException {
        public InvalidEvidenceException() { this("CURRENT_EVIDENCE_BINDING_INVALID"); }
        public InvalidEvidenceException(String reason) { this(reason, null); }
        public InvalidEvidenceException(String reason, AnalysisJob source) {
            this(reason, source == null ? null : source.getId(),
                    source == null || source.getEpisode() == null ? null : source.getEpisode().getId(),
                    source == null ? null : source.getSourceEpisodeNo());
        }
        public InvalidEvidenceException(String reason, UUID jobId, UUID episodeId, Integer episodeNo) {
            super(AnalysisJobErrorCode.ANALYSIS_CURRENT_EVIDENCE_INVALID, diagnosticContext(reason, jobId, episodeId, episodeNo));
        }
    }

    private static Map<String, Object> diagnosticContext(String reason, UUID jobId, UUID episodeId, Integer episodeNo) {
        return Map.of("failureDiagnostics", new AnalysisFailureDiagnostics(1, "SPRING", "UNKNOWN", reason,
                "CURRENT_EVIDENCE_SOURCE_READ_FAILED".equals(reason) ? "ANALYSIS_CURRENT_EVIDENCE_SOURCE_UNAVAILABLE" : "ANALYSIS_CURRENT_EVIDENCE_INVALID",
                null, null, null, jobId, null, episodeId, episodeNo));
    }
}
