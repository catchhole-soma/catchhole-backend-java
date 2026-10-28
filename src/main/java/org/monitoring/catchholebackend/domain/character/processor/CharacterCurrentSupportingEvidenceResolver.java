package org.monitoring.catchholebackend.domain.character.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.SettingBookAutomaticEvidenceValidator;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.EvidenceValidationResult;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.stereotype.Component;

/** Source reads occur only while an input or a new journal snapshot is frozen. */
@Component
@RequiredArgsConstructor
@Slf4j
public class CharacterCurrentSupportingEvidenceResolver {
    private final ObjectStorageService storage;
    private final SettingBookAutomaticEvidenceValidator quoteValidator;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public Capture begin(Work work) { return new Capture(work.getId()); }

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
        return captureCandidate(capture, candidate, false, candidate.getEvidenceSpans());
    }

    public ObjectNode captureRetainedCandidate(Capture capture, SettingCandidate candidate) {
        return captureCandidate(capture, candidate, true, candidate.getEvidenceSpans());
    }

    public ArrayNode captureRetainedIdentityEvidence(Capture capture, SettingCandidate discovery) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        if ("VERIFIED".equals(captureRetainedCandidate(capture, discovery).path("availability").asText())) {
            result.addAll((ArrayNode) discovery.getEvidenceSpans().deepCopy());
        }
        if (discovery.isReviewedAutomatically() && discovery.getAnalysisJob() != null
                && discovery.getAnalysisJob().isSettingBookSource() && discovery.getRawAiResultJson() != null) {
            JsonNode proof = discovery.getRawAiResultJson().path("setting_book_identity_proof");
            for (JsonNode member : proof.path("members")) {
                JsonNode span = member.path("profileEvidence");
                if (member.path("targetRef").equals(proof.path("parentTargetRef")) && span.isObject()
                        && "VERIFIED".equals(captureCandidate(capture, discovery, true,
                                JsonNodeFactory.instance.arrayNode().add(span)).path("availability").asText())) {
                    result.add(span.deepCopy());
                }
            }
        }
        return result;
    }

    private ObjectNode captureCandidate(Capture capture, SettingCandidate candidate, boolean retained, JsonNode evidence) {
        AnalysisJob job = candidate.getAnalysisJob();
        if (job == null) return unavailable(candidate.getId(), null, "LEGACY_NO_MANIFEST", candidate.hasUserEditedContent());
        if (!same(job.getWork().getId(), capture.workId) || !same(candidate.getWork().getId(), capture.workId)
                || job.getId() == null || job.getJobType() != AnalysisJobType.SETTING_EXTRACTION) throw invalid();
        boolean noManifest = job.getSourceContentHash() == null && job.getSourceContentS3Key() == null;
        if (noManifest) return unavailable(candidate.getId(), job.getId(), "LEGACY_NO_MANIFEST", candidate.hasUserEditedContent());
        if (job.getSourceContentHash() == null || !job.getSourceContentHash().matches("[0-9a-f]{64}")
                || job.getSourceContentS3Key() == null || job.getSourceContentS3Key().isBlank()) throw invalid();
        ObjectNode source = source(job);
        if (evidence == null || evidence.isNull() || evidence.isArray() && evidence.isEmpty()) {
            ObjectNode row = unavailable(candidate.getId(), job.getId(), "NO_RECORDED_QUOTE", candidate.hasUserEditedContent());
            row.set("source", source);
            storageBinding(row, job);
            return row;
        }
        String content = original(capture, job);
        EvidenceValidationResult validation = quoteValidator.validate(content, evidence);
        if (retained && validation == EvidenceValidationResult.QUOTE_NOT_FOUND) {
            ObjectNode row = unavailable(candidate.getId(), job.getId(), "QUOTE_NOT_FOUND", candidate.hasUserEditedContent());
            row.set("source", source);
            storageBinding(row, job);
            row.set("narrativeTime", captureNarrativeTime(capture, candidate, candidate.getNarrativeTime()));
            log.warn("기존 확정 근거의 일반 인용을 확인할 수 없습니다. sourceCandidateId={}, sourceAnalysisJobId={}, reason=QUOTE_NOT_FOUND, spanCount={}",
                    candidate.getId(), job.getId(), evidence.size());
            return row;
        }
        if (validation != EvidenceValidationResult.VALID) throw invalid();
        ObjectNode row = base(candidate.getId(), job.getId(), candidate.hasUserEditedContent());
        row.put("availability", "VERIFIED").putNull("unavailableReason");
        row.set("source", source);
        ArrayNode spans = row.putArray("evidenceSpans");
        for (JsonNode span : evidence) {
            ObjectNode frozen = spans.addObject().put("quote", span.path("quote").asText());
            copyOffset(frozen, span, "startOffset", "start_offset");
            copyOffset(frozen, span, "endOffset", "end_offset");
        }
        storageBinding(row, job);
        row.set("narrativeTime", captureNarrativeTime(capture, candidate, candidate.getNarrativeTime()));
        return row;
    }

    public JsonNode captureNarrativeTime(Capture capture, SettingCandidate candidate, JsonNode time) {
        if (time == null || time.isNull()) return JsonNodeFactory.instance.nullNode();
        for (String field : List.of("factTime", "referenceTime")) {
            if (time.path(field).isObject() && time.path(field).path("evidenceSpans").isArray()
                    && time.path(field).path("evidenceSpans").isEmpty()) return JsonNodeFactory.instance.nullNode();
        }
        AnalysisJob job = candidate.getAnalysisJob();
        if (job == null || !same(candidate.getWork().getId(), capture.workId)
                || !same(job.getWork().getId(), capture.workId) || !time.isObject() || time.size() != 2
                || !time.has("factTime") || !time.has("referenceTime")) throw invalid();
        String content = original(capture, job);
        for (String field : List.of("factTime", "referenceTime")) {
            JsonNode point = time.get(field);
            if (point.isNull()) continue;
            CharacterNarrativeTime.requirePoint(point);
            if (!quoteValidator.matches(content, point.path("evidenceSpans"))) throw invalid();
            for (JsonNode span : point.path("evidenceSpans")) {
                UUID chunk = UUID.fromString(span.path("sourceChunkId").asText());
                String sql = job.isSettingBookSource()
                        ? "select start_offset, end_offset, chunk_text from setting_book_chunks where id = ? and analysis_job_id = ? and setting_book_revision_id = ?"
                        : "select start_offset, end_offset, chunk_text from episode_chunks where id = ? and episode_id = ?";
                Object[] args = job.isSettingBookSource()
                        ? new Object[]{chunk, job.getId(), job.getSourceSettingBookRevision().getId()}
                        : new Object[]{chunk, job.getEpisode().getId()};
                var ranges = jdbc.query(sql, (rs, row) -> new Object[]{rs.getInt(1), rs.getInt(2), rs.getString(3)}, args);
                // Episode chunks are replaceable processing artifacts. Older reanalysis removed
                // their UUIDs even when the immutable source and grounded offsets stayed identical.
                // Recover only retained observations; new extraction still needs its actual chunk.
                if (ranges.isEmpty() && !job.isSettingBookSource()
                        && (job.getStatus() == AnalysisJobStatus.SUCCEEDED || !candidate.isPendingReview())
                        && Integer.valueOf(0).equals(jdbc.queryForObject(
                                "select count(*) from episode_chunks where id = ?", Integer.class, chunk))) {
                    continue;
                }
                if (ranges.size() != 1) throw invalid();
                Object[] range = ranges.getFirst();
                int start = (int) range[0], end = (int) range[1];
                if (start < 0 || end <= start || span.path("startOffset").asInt() < start || span.path("endOffset").asInt() > end
                        || end > content.codePointCount(0, content.length())
                        || !Objects.equals(range[2], content.substring(content.offsetByCodePoints(0, start), content.offsetByCodePoints(0, end)))) throw invalid();
            }
        }
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

    private String original(Capture capture, AnalysisJob job) {
        String binding = job.getSourceContentS3Key()+"\n"+job.getSourceContentS3Version()+"\n"+job.getSourceContentHash();
        return capture.contents.computeIfAbsent(binding, ignored -> {
            // Episode/revision writers use immutable unique keys. Never read the current Episode pointer.
            String content = storage.getText(job.getSourceContentS3Key());
            if (content == null || !job.getSourceContentHash().equals(storage.contentHash(content))) {
                throw new IllegalStateException("CURRENT 원문 근거의 고정 저장본을 읽거나 검증할 수 없습니다.");
            }
            return content;
        });
    }

    private ObjectNode source(AnalysisJob job) {
        ObjectNode node = JsonNodeFactory.instance.objectNode().put("sourceKind", job.getSourceKind().name());
        node.putNull("episodeId").putNull("episodeNo").putNull("settingBookId").putNull("revisionId")
                .putNull("revisionNo").put("contentHash", job.getSourceContentHash())
                .putNull("referenceTimeKind").putNull("referenceEpisodeNo");
        if (job.isSettingBookSource()) {
            var book = job.getSourceSettingBook();
            var revision = job.getSourceSettingBookRevision();
            if (book == null || revision == null || book.getBatch() == null
                    || !same(book.getBatch().getWork().getId(), job.getWork().getId())
                    || !same(revision.getSettingBook().getId(), book.getId())
                    || !Objects.equals(revision.getContentHash(), job.getSourceContentHash())
                    || !Objects.equals(revision.getContentS3Key(), job.getSourceContentS3Key())
                    || !Objects.equals(revision.getContentS3Version(), job.getSourceContentS3Version())) throw invalid();
            node.put("settingBookId", book.getId().toString()).put("revisionId", revision.getId().toString())
                    .put("revisionNo", revision.getRevisionNo()).put("referenceTimeKind", revision.getReferenceTimeKind().name())
                    .put("referenceEpisodeNo", revision.getReferenceEpisodeNo());
        } else {
            var episode = job.getEpisode();
            if (episode == null || !same(episode.getWork().getId(), job.getWork().getId())
                    || job.getSourceEpisodeNo() == null || job.getSourceEpisodeNo() < 1) throw invalid();
            node.put("episodeId", episode.getId().toString()).put("episodeNo", job.getSourceEpisodeNo());
        }
        return node;
    }

    private void storageBinding(ObjectNode row, AnalysisJob job) {
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

    private void copyOffset(ObjectNode target, JsonNode source, String camel, String snake) {
        JsonNode value = source.has(camel) ? source.get(camel) : source.get(snake);
        if (value == null || value.isNull()) target.putNull(camel); else target.set(camel, value.deepCopy());
    }

    private boolean same(UUID left, UUID right) { return left != null && left.equals(right); }
    private InvalidEvidenceException invalid() { return new InvalidEvidenceException(); }

    public static final class Capture {
        private final UUID workId;
        private final Map<String,String> contents = new HashMap<>();
        private Capture(UUID workId) { this.workId = Objects.requireNonNull(workId); }
    }

    /** Dedicated deterministic shape/binding failure; excludes transient source storage failures. */
    public static final class InvalidEvidenceException extends RuntimeException {
        public InvalidEvidenceException() { super("FROZEN_CURRENT_EVIDENCE_INVALID: 고정 현재 근거의 결속 또는 인용이 올바르지 않습니다."); }
    }
}
