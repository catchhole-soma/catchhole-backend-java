package org.monitoring.catchholebackend.domain.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
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

    @Override
    public String domain() {
        return "references";
    }

    @Override
    public JsonNode capture(Work work) {
        ObjectNode references = JsonNodeFactory.instance.objectNode();
        for (SettingCandidate candidate : characters.findAllByWorkIdAndReviewStatusOrderByCreatedAtDesc(
                work.getId(), SettingCandidateReviewStatus.DISMISSED)) {
            if (candidate.isUserModified()) {
                characterDescriptor(candidate).ifPresent(descriptor -> putReference(references, "characters",
                        candidate.getId(), candidate.getAnalysisJob(), descriptor));
            }
        }
        for (WorldSettingCandidate candidate : worldSettings.findAllByWorkIdAndReviewStatusOrderByCreatedAtDesc(
                work.getId(), WorldSettingReviewStatus.DISMISSED)) {
            if (candidate.getReviewedBy() != null && candidate.getFinalOperation() == WorldSettingOperation.EXCLUDE) {
                worldDescriptor(candidate).ifPresent(descriptor -> putReference(references, "worldSettings",
                        candidate.getId(), candidate.getAnalysisJob(), descriptor));
            }
        }
        return references;
    }

    public Optional<JsonNode> match(SettingCandidate candidate, JsonNode state) {
        return characterDescriptor(candidate).flatMap(descriptor -> find("characters", descriptor, state));
    }

    public Optional<JsonNode> match(WorldSettingCandidate candidate, JsonNode state) {
        return worldDescriptor(candidate).flatMap(descriptor -> find("worldSettings", descriptor, state));
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

    private Optional<JsonNode> find(String domain, ObjectNode descriptor, JsonNode state) {
        if (state == null) {
            return Optional.empty();
        }
        String fingerprint = journal.hash(descriptor);
        for (JsonNode reference : state.path("references")) {
            if (KIND.equals(reference.path("kind").asText())
                    && POLICY.equals(reference.path("policy").asText())
                    && domain.equals(reference.path("domain").asText())
                    && fingerprint.equals(reference.path("fingerprint").asText())) {
                return Optional.of(reference.deepCopy());
            }
        }
        return Optional.empty();
    }

    private void putReference(ObjectNode references, String domain, UUID id, AnalysisJob source,
            ObjectNode descriptor) {
        ObjectNode reference = references.putObject("human-rejection:" + domain + ":" + id);
        reference.put("kind", KIND);
        reference.put("policy", POLICY);
        reference.put("domain", domain);
        reference.put("fingerprint", journal.hash(descriptor));
        reference.put("rejectedCandidateId", id.toString());
        reference.put("rejectedEpisodeId", source.getEpisode().getId().toString());
        reference.put("rejectedEpisodeNo", source.getSourceEpisodeNo());
        reference.put("rejectedSourceHash", source.getSourceContentHash());
    }

    private Optional<ObjectNode> characterDescriptor(SettingCandidate candidate) {
        if (candidate.isCharacterDiscovery()) {
            return discoveryDescriptor(candidate);
        }
        ObjectNode result = source(candidate.getAnalysisJob(), candidate.getEvidenceSpans());
        JsonNode identity = characterIdentity(candidate);
        if (result == null || identity == null || candidate.getEpisode() == null
                || !candidate.getEpisode().getId().equals(candidate.getAnalysisJob().getEpisode().getId())) {
            return Optional.empty();
        }
        result.set("identity", identity);
        result.put("path", candidate.getAttributeName());
        result.put("value", candidate.getAttributeValue());
        result.put("valueType", candidate.getValueType() == null ? null : candidate.getValueType().name());
        result.set("valueJson", candidate.getValueJson());
        return Optional.of(result);
    }

    private Optional<ObjectNode> discoveryDescriptor(SettingCandidate candidate) {
        ObjectNode result = source(candidate.getAnalysisJob(), candidate.getEvidenceSpans());
        JsonNode raw = candidate.getRawAiResultJson();
        if (result == null || candidate.getEpisode() == null
                || !candidate.getEpisode().getId().equals(candidate.getAnalysisJob().getEpisode().getId())
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

    private JsonNode characterIdentity(SettingCandidate candidate) {
        if (candidate.getMatchedCharacterId() != null) {
            return JsonNodeFactory.instance.objectNode().put("actualCharacterId", candidate.getMatchedCharacterId().toString());
        }
        UUID anchorId = anchorId(candidate.getProvisionalSubjectKey(), "provisional-character:");
        if (anchorId == null) {
            return null;
        }
        SettingCandidate anchor = characters.findByIdAndWorkId(anchorId, candidate.getWork().getId()).orElse(null);
        if (anchor == null || !anchor.isCharacterDiscovery()) {
            return null;
        }
        ObjectNode identity = source(anchor.getAnalysisJob(), anchor.getEvidenceSpans());
        if (identity != null) {
            identity.put("name", anchor.getEntityName());
            identity.put("rawMention", anchor.getRawEntityMention());
        }
        return identity;
    }

    private Optional<ObjectNode> worldDescriptor(WorldSettingCandidate candidate) {
        ObjectNode result = source(candidate.getAnalysisJob(), candidate.getEvidenceSpans());
        JsonNode identity = worldIdentity(candidate);
        if (result == null || identity == null || candidate.getSourceEpisode() == null
                || !candidate.getSourceEpisode().getId().equals(candidate.getAnalysisJob().getEpisode().getId())) {
            return Optional.empty();
        }
        result.set("identity", identity);
        result.put("category", candidate.getCategory().name());
        result.put("scope", candidate.getScopeName());
        result.put("path", candidate.getSettingName());
        result.put("value", candidate.getExtractedValue());
        return Optional.of(result);
    }

    private JsonNode worldIdentity(WorldSettingCandidate candidate) {
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
        WorldSettingCandidate anchor = worldSettings.findByIdAndWorkId(anchorId, candidate.getWork().getId()).orElse(null);
        if (anchor == null) {
            return null;
        }
        ObjectNode identity = source(anchor.getAnalysisJob(), anchor.getEvidenceSpans());
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

    private ObjectNode source(AnalysisJob job, JsonNode evidence) {
        // 과거 legacy Job의 현재 Episode hash를 빌려서 불변 출처처럼 취급하지 않는다.
        if (job == null || !job.isOrderedProvisional() || job.getEpisode() == null
                || job.getEpisode().getId() == null || job.getSourceEpisodeNo() == null
                || job.getSourceContentHash() == null || evidence == null || !evidence.isArray() || evidence.isEmpty()) {
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
        result.put("episodeId", job.getEpisode().getId().toString());
        result.put("episodeNo", job.getSourceEpisodeNo());
        result.put("contentHash", job.getSourceContentHash());
        ArrayNode normalized = result.putArray("evidence");
        spans.forEach(normalized::add);
        return result;
    }
}
