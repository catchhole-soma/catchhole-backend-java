package org.monitoring.catchholebackend.domain.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobSourceRepository;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobSourceView;
import org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.springframework.stereotype.Component;

/** 같은 원문 주장의 사용자 반려를 보존한다. 해시는 서버 판정용이며 사실/LLM 문맥이 아니다. */
@Component
@RequiredArgsConstructor
public class AnalysisHumanRejectionPolicy implements AnalysisStateSource {
    public static final String KIND = "HUMAN_REJECTION_POLICY";
    public static final String POLICY = "EXACT_SOURCE_CLAIM_V1";
    private final SettingCandidateRepository characters;
    private final WorldSettingCandidateRepository worldSettings;
    private final AnalysisStateJournal journal;
    private final AnalysisJobSourceRepository sourceJobs;

    @Override
    public String domain() {
        return "references";
    }

    @Override
    public JsonNode capture(Work work) {
        ObjectNode references = JsonNodeFactory.instance.objectNode();
        Map<UUID, AnalysisJobSourceView> sources = new HashMap<>();
        sourceJobs.findExtractionSourcesForWork(work.getId()).forEach(source -> sources.put(source.getId(), source));
        MatchingSession matching = new MatchingSession(sources);
        for (SettingCandidate candidate : characters.findAllByWorkIdAndReviewStatusOrderByCreatedAtDesc(
                work.getId(), SettingCandidateReviewStatus.DISMISSED)) {
            if (candidate.isUserModified()) {
                characterDescriptor(candidate, matching).ifPresent(descriptor -> putReference(references, "characters",
                        candidate.getId(), matching.metadata(candidate.getAnalysisJob()), descriptor));
            }
        }
        for (WorldSettingCandidate candidate : worldSettings.findAllByWorkIdAndReviewStatusOrderByCreatedAtDesc(
                work.getId(), WorldSettingReviewStatus.DISMISSED)) {
            if (candidate.getReviewedBy() != null && candidate.getFinalOperation() == WorldSettingOperation.EXCLUDE) {
                worldDescriptor(candidate, matching).ifPresent(descriptor -> putReference(references, "worldSettings",
                        candidate.getId(), matching.metadata(candidate.getAnalysisJob()), descriptor));
            }
        }
        return references;
    }

    public Optional<JsonNode> match(SettingCandidate candidate, JsonNode state) {
        return beginMatching().match(candidate, state);
    }

    public Optional<JsonNode> match(WorldSettingCandidate candidate, JsonNode state) {
        return beginMatching().match(candidate, state);
    }

    /** 한 요청의 후보 순회에서만 사용한다. 현재 references는 각 match 호출에서 다시 읽는다. */
    public MatchingSession beginMatching() {
        return new MatchingSession(null);
    }

    public final class MatchingSession {
        private final Map<AnchorKey, Optional<SettingCandidate>> characterAnchors = new HashMap<>();
        private final Map<AnchorKey, Optional<WorldSettingCandidate>> worldAnchors = new HashMap<>();

        // Capture reads historical source scalars; matching a live candidate keeps its in-transaction entity semantics.
        private final Map<UUID, AnalysisJobSourceView> sourceMetadata;

        private MatchingSession(Map<UUID, AnalysisJobSourceView> sourceMetadata) {
            this.sourceMetadata = sourceMetadata;
        }

        private AnalysisJobSourceView metadata(AnalysisJob job) {
            if (job == null) return null;
            return sourceMetadata.computeIfAbsent(job.getId(), id -> sourceJobs.findSourceById(id)
                    .orElseThrow(() -> new IllegalStateException("반려 이력의 분석 출처가 없습니다.")));
        }

        private ClaimSource source(AnalysisJob job) {
            return sourceMetadata == null ? ClaimSource.from(job) : ClaimSource.from(metadata(job));
        }

        public Optional<JsonNode> match(SettingCandidate candidate, JsonNode state) {
            List<JsonNode> references = rejectionReferences("characters", state);
            if (references.isEmpty()) return Optional.empty();
            return characterDescriptor(candidate, this).flatMap(descriptor -> find(descriptor, references));
        }

        public Optional<JsonNode> match(WorldSettingCandidate candidate, JsonNode state) {
            List<JsonNode> references = rejectionReferences("worldSettings", state);
            if (references.isEmpty()) return Optional.empty();
            return worldDescriptor(candidate, this).flatMap(descriptor -> find(descriptor, references));
        }
    }

    private record AnchorKey(UUID workId, UUID candidateId) {
    }

    public AnalysisStateChange referenceChange(UUID sourceId, JsonNode rejection) {
        ObjectNode value = rejection.deepCopy();
        value.put("operation", "EXCLUDE");
        value.put("reason", "USER_REJECTED");
        value.put("candidateId", sourceId.toString());
        value.putArray("sourceCandidateIds").add(sourceId.toString());
        String eventId = "human-rejection:" + sourceId;
        return new AnalysisStateChange(eventId, List.of("references", eventId), value, false,
                "EXCLUDE", List.of(sourceId));
    }

    private List<JsonNode> rejectionReferences(String domain, JsonNode state) {
        if (state == null) return List.of();
        List<JsonNode> references = new ArrayList<>();
        for (JsonNode reference : state.path("references")) {
            if (KIND.equals(reference.path("kind").asText())
                    && POLICY.equals(reference.path("policy").asText())
                    && domain.equals(reference.path("domain").asText())) {
                references.add(reference);
            }
        }
        return references;
    }

    private Optional<JsonNode> find(ObjectNode descriptor, List<JsonNode> references) {
        String fingerprint = journal.hash(descriptor);
        for (JsonNode reference : references) {
            if (fingerprint.equals(reference.path("fingerprint").asText())) {
                return Optional.of(reference.deepCopy());
            }
        }
        return Optional.empty();
    }

    private void putReference(ObjectNode references, String domain, UUID id, AnalysisJobSourceView source,
            ObjectNode descriptor) {
        ObjectNode reference = references.putObject("human-rejection:" + domain + ":" + id);
        reference.put("kind", KIND);
        reference.put("policy", POLICY);
        reference.put("domain", domain);
        reference.put("fingerprint", journal.hash(descriptor));
        reference.put("rejectedCandidateId", id.toString());
        reference.put("rejectedEpisodeId", source.getEpisodeId() == null ? null : source.getEpisodeId().toString());
        org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisSourceMetadata.put(reference, source);
        reference.put("rejectedEpisodeNo", source.getSourceEpisodeNo());
        reference.put("rejectedSourceHash", source.getSourceContentHash());
    }

    private Optional<ObjectNode> characterDescriptor(SettingCandidate candidate, MatchingSession matching) {
        if (candidate.isCharacterDiscovery()) {
            return discoveryDescriptor(candidate, matching);
        }
        ObjectNode result = source(matching.source(candidate.getAnalysisJob()), candidate.getEvidenceSpans());
        JsonNode identity = characterIdentity(candidate, matching);
        if (result == null || identity == null || !sameSource(matching.source(candidate.getAnalysisJob()), candidate.getEpisode(), candidate.getSourceSettingBook(), candidate.getSourceSettingBookRevision())) {
            return Optional.empty();
        }
        result.set("identity", identity);
        result.put("path", candidate.getAttributeName());
        result.put("value", candidate.getAttributeValue());
        result.put("valueType", candidate.getValueType() == null ? null : candidate.getValueType().name());
        result.set("valueJson", candidate.getValueJson());
        return Optional.of(result);
    }

    private Optional<ObjectNode> discoveryDescriptor(SettingCandidate candidate, MatchingSession matching) {
        ObjectNode result = source(matching.source(candidate.getAnalysisJob()), candidate.getEvidenceSpans());
        JsonNode raw = candidate.getRawAiResultJson();
        if (result == null || !sameSource(matching.source(candidate.getAnalysisJob()), candidate.getEpisode(), candidate.getSourceSettingBook(), candidate.getSourceSettingBookRevision())
                || raw == null || !raw.isObject()
                || !"CHARACTER_DISCOVERY".equals(raw.path("candidate_kind").asText())
                || !"CHARACTER".equals(raw.path("entity_type").asText())
                || !raw.path("entity_name").isTextual() || raw.path("entity_name").asText().isBlank()) {
            return Optional.empty();
        }
        result.put("candidateKind", "CHARACTER_DISCOVERY");
        result.put("name", candidate.getEntityName());
        result.put("rawMention", candidate.getRawEntityMention());
        // 재추출마다 바뀌는 chunk/후보 UUID와 해소 결과는 발견 원문 주장의 일부가 아니다.
        ObjectNode original = result.putObject("originalDiscovery");
        original.set("candidateKind", raw.path("candidate_kind"));
        original.set("entityType", raw.path("entity_type"));
        original.set("name", raw.path("entity_name"));
        original.set("rawMention", raw.has("raw_entity_mention") ? raw.get("raw_entity_mention")
                : JsonNodeFactory.instance.nullNode());
        return Optional.of(result);
    }

    private JsonNode characterIdentity(SettingCandidate candidate, MatchingSession matching) {
        if (candidate.getMatchedCharacterId() != null) {
            return JsonNodeFactory.instance.objectNode().put("actualCharacterId", candidate.getMatchedCharacterId().toString());
        }
        UUID anchorId = anchorId(candidate.getProvisionalSubjectKey(), "provisional-character:");
        if (anchorId == null) {
            return null;
        }
        AnchorKey key = new AnchorKey(candidate.getWork().getId(), anchorId);
        SettingCandidate anchor = matching.characterAnchors.computeIfAbsent(key,
                lookup -> characters.findByIdAndWorkId(lookup.candidateId(), lookup.workId())).orElse(null);
        if (anchor == null || !anchor.isCharacterDiscovery()) {
            return null;
        }
        ObjectNode identity = source(matching.source(anchor.getAnalysisJob()), anchor.getEvidenceSpans());
        if (identity != null) {
            identity.put("name", anchor.getEntityName());
            identity.put("rawMention", anchor.getRawEntityMention());
        }
        return identity;
    }

    private Optional<ObjectNode> worldDescriptor(WorldSettingCandidate candidate, MatchingSession matching) {
        ObjectNode result = source(matching.source(candidate.getAnalysisJob()), candidate.getEvidenceSpans());
        JsonNode identity = worldIdentity(candidate, matching);
        if (result == null || identity == null || !sameSource(matching.source(candidate.getAnalysisJob()), candidate.getSourceEpisode(), candidate.getSourceSettingBook(), candidate.getSourceSettingBookRevision())) {
            return Optional.empty();
        }
        result.set("identity", identity);
        result.put("category", candidate.getCategory().name());
        result.put("scope", candidate.getScopeName());
        result.put("path", candidate.getSettingName());
        result.put("value", candidate.getExtractedValue());
        return Optional.of(result);
    }

    private JsonNode worldIdentity(WorldSettingCandidate candidate, MatchingSession matching) {
        if (candidate.getTargetWorldSetting() != null) {
            return JsonNodeFactory.instance.objectNode().put("actualWorldSettingId", candidate.getTargetWorldSetting().getId().toString());
        }
        JsonNode ids = candidate.getResolvedTargetWorldSettingIds();
        if (ids != null && ids.size() == 1 && candidate.getProvisionalSubjectKey() == null) {
            return JsonNodeFactory.instance.objectNode().put("actualWorldSettingId", ids.get(0).asText());
        }
        UUID anchorId = anchorId(candidate.getProvisionalSubjectKey(), "provisional-world:");
        if (anchorId == null) {
            return null;
        }
        AnchorKey key = new AnchorKey(candidate.getWork().getId(), anchorId);
        WorldSettingCandidate anchor = matching.worldAnchors.computeIfAbsent(key,
                lookup -> worldSettings.findByIdAndWorkId(lookup.candidateId(), lookup.workId())).orElse(null);
        if (anchor == null) {
            return null;
        }
        ObjectNode identity = source(matching.source(anchor.getAnalysisJob()), anchor.getEvidenceSpans());
        if (identity != null) {
            identity.put("name", anchor.getSubjectName());
            identity.put("category", anchor.getCategory().name());
        }
        return identity;
    }

    private UUID anchorId(String ref, String prefix) {
        if (ref == null || !ref.startsWith(prefix)) {
            return null;
        }
        try {
            return UUID.fromString(ref.substring(prefix.length()));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean sameSource(ClaimSource job, org.monitoring.catchholebackend.domain.episode.entity.Episode episode,
            org.monitoring.catchholebackend.domain.upload.entity.UploadFile book,
            org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision revision) {
        if (job == null) return false;
        if (job.settingBook()) return episode == null && book != null && revision != null
                && book.getId().equals(job.settingBookId())
                && revision.getId().equals(job.settingBookRevisionId())
                && revision.getSettingBook().getId().equals(book.getId())
                && book.getBatch().getWork().getId().equals(job.workId());
        return episode != null && job.episodeId() != null && episode.getId().equals(job.episodeId());
    }

    private ObjectNode source(ClaimSource job, JsonNode evidence) {
        // 과거 legacy Job의 현재 Episode hash를 빌려서 불변 출처처럼 취급하지 않는다.
        if (job == null || !job.ordered()
                || (job.settingBook() ? job.settingBookId() == null || job.settingBookRevisionId() == null
                    : job.episodeId() == null || job.episodeNo() == null)
                || job.contentHash() == null || evidence == null || !evidence.isArray() || evidence.isEmpty()) {
            return null;
        }
        List<ObjectNode> spans = new ArrayList<>();
        for (JsonNode span : evidence) {
            JsonNode start = span.has("startOffset") ? span.path("startOffset") : span.path("start_offset");
            JsonNode end = span.has("endOffset") ? span.path("endOffset") : span.path("end_offset");
            if (!span.path("quote").isTextual() || span.path("quote").asText().isBlank()
                    || !start.isIntegralNumber() || !end.isIntegralNumber()
                    || start.asLong() < 0 || end.asLong() <= start.asLong()) {
                return null;
            }
            ObjectNode normalized = JsonNodeFactory.instance.objectNode();
            normalized.put("quote", span.path("quote").asText());
            normalized.put("startOffset", start.asLong());
            normalized.put("endOffset", end.asLong());
            spans.add(normalized);
        }
        spans.sort(Comparator.comparing(journal::canonicalJson));
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        if (job.settingBook()) {
            // Identity/content/evidence survive a repeated extraction; attempts and revision UUIDs are history metadata.
            result.put("sourceKind", "SETTING_BOOK");
            result.put("settingBookId", job.settingBookId().toString());
            result.put("referenceTimeKind", job.referenceTimeKind().name());
            result.put("referenceEpisodeNo", job.referenceEpisodeNo());
        } else {
            result.put("episodeId", job.episodeId().toString());
            result.put("episodeNo", job.episodeNo());
        }
        result.put("contentHash", job.contentHash());
        ArrayNode normalized = result.putArray("evidence");
        spans.forEach(normalized::add);
        return result;
    }

    /** Only capture uses metadata; live matching preserves the original lazy entity reads. */
    private record ClaimSource(AnalysisJob entity, AnalysisJobSourceView metadata) {
        static ClaimSource from(AnalysisJobSourceView job) {
            return job == null ? null : new ClaimSource(null, job);
        }

        static ClaimSource from(AnalysisJob job) {
            return job == null ? null : new ClaimSource(job, null);
        }

        boolean ordered() { return metadata != null ? metadata.isOrderedProvisional() : entity.isOrderedProvisional(); }
        boolean settingBook() { return metadata != null ? metadata.isSettingBookSource() : entity.isSettingBookSource(); }
        UUID workId() { return metadata != null ? metadata.getWorkId() : entity.getWork().getId(); }
        UUID episodeId() {
            return metadata != null ? metadata.getEpisodeId()
                    : entity.getEpisode() == null ? null : entity.getEpisode().getId();
        }
        Integer episodeNo() { return metadata != null ? metadata.getSourceEpisodeNo() : entity.getSourceEpisodeNo(); }
        UUID settingBookId() {
            return metadata != null ? metadata.getSourceSettingBookId()
                    : entity.getSourceSettingBook() == null ? null : entity.getSourceSettingBook().getId();
        }
        UUID settingBookRevisionId() {
            return metadata != null ? metadata.getSourceSettingBookRevisionId()
                    : entity.getSourceSettingBookRevision() == null ? null : entity.getSourceSettingBookRevision().getId();
        }
        SettingBookReferenceTimeKind referenceTimeKind() {
            return metadata != null ? metadata.getSourceReferenceTimeKind() : entity.getSourceSettingBookRevision().getReferenceTimeKind();
        }
        Integer referenceEpisodeNo() {
            return metadata != null ? metadata.getSourceReferenceEpisodeNo() : entity.getSourceSettingBookRevision().getReferenceEpisodeNo();
        }
        String contentHash() { return metadata != null ? metadata.getSourceContentHash() : entity.getSourceContentHash(); }
    }
}
