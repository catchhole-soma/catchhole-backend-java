package org.monitoring.catchholebackend.domain.character.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind;
import org.monitoring.catchholebackend.domain.character.dto.response.WorkerCharacterAdvisoryCurrentContextPayload;
import org.monitoring.catchholebackend.domain.character.dto.response.WorkerCharacterAdvisoryCurrentContextPayload.*;
import org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind;
import org.monitoring.catchholebackend.global.exception.AppException;

/** Uses only the supplied frozen projection, never live facts, candidates or storage. */
public final class CharacterAdvisoryCurrentContextMapper {
    private final ObjectMapper values = new ObjectMapper();
    private final AnalysisStateJournal journal = new AnalysisStateJournal();
    private static final Set<String> REASONS = Set.of("LEGACY_INPUT_WITHOUT_FROZEN_EVIDENCE", "LEGACY_NO_MANIFEST",
            "NO_RECORDED_QUOTE", "HUMAN_CURRENT_WITHOUT_SOURCE", "SOURCE_EVIDENCE_PURGED", "QUOTE_NOT_FOUND");

    public WorkerCharacterAdvisoryCurrentContextPayload toResponse(AnalysisJob job, JsonNode owner,
            UUID expectedActual, String expectedProvisional) {
        if (job.isOrderedProvisional() && (job.getAnalysisRunId() == null || job.getRunGeneration() == null
                || job.getRunGeneration() < 1 || !hash(job.getInputStateHash()) || !hash(job.getSourceContentHash()))) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_RUN_STATE_CONFLICT);
        }
        UUID actual = uuid(required(owner, "actualCharacterId"), true);
        String provisional = text(owner, "provisionalSubjectKey", true);
        if ((actual == null) == (provisional == null) || !Objects.equals(actual, expectedActual)
                || !Objects.equals(provisional, expectedProvisional)) throw invalid();
        if (provisional != null && (!provisional.startsWith("provisional-character:")
                || !provisional.equals("provisional-character:" + parseUuid(provisional.substring("provisional-character:".length()))))) throw invalid();
        int version = 0;
        if (owner.has("currentSupportingEvidenceVersion")) {
            JsonNode marker = owner.get("currentSupportingEvidenceVersion");
            if (!marker.isIntegralNumber() || !marker.canConvertToInt() || marker.intValue() != 1) throw invalid();
            version = 1;
        }
        JsonNode slots = required(owner, "slots");
        if (!slots.isObject()) throw invalid();
        List<CurrentSlot> result = new ArrayList<>();
        int origins = 0, spans = 0;
        for (String slotRef : keys(slots)) {
            JsonNode slot = slots.get(slotRef);
            CharacterFactType type = enumeration(CharacterFactType.class, text(slot, "factType", false));
            String key = text(slot, "factKey", false);
            if (!slotRef.equals(type.name()+":"+key)) throw invalid();
            JsonNode scalar = required(slot, "factValue");
            if (!scalar.isNull() && !scalar.isTextual()) throw invalid();
            String value = scalar.isNull() ? null : scalar.textValue();
            JsonNode valueJson = required(slot, "valueJson");
            requireJson(valueJson);
            JsonNode provenance = required(slot, "provenance");
            if (!provenance.isObject()) throw invalid();
            Set<UUID> sourceIds = sourceIds(provenance, version);
            List<SupportingOrigin> supporting = version == 0 ? legacy(sourceIds) : origins(slot, sourceIds);
            origins += supporting.size();
            spans += supporting.stream().mapToInt(origin -> origin.evidenceSpans().size()).sum();
            if (result.size() >= 4096 || origins > 4096 || spans > 4096) throw invalid();
            JsonNode times = provenance.get("currentSourceTimes");
            if (version == 1 && (times == null || !times.isArray())) throw invalid();
            if (times != null && !times.isArray()) throw invalid();
            if (version == 1) for (JsonNode time : times) {
                exact(time, Set.of("sourceKind", "sourceEpisodeNo", "referenceTimeKind", "referenceEpisodeNo", "effectiveFromEpisodeNo", "temporalScope"));
            }
            List<org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisCurrentSourceTimePayload> sourceTimes;
            try { sourceTimes = AnalysisCurrentSourceTimeMapper.fromJson(times); }
            catch (IllegalArgumentException exception) { throw invalid(); }
            JsonNode protectedValue = provenance.get("currentValueProtected");
            if (version == 1 && (protectedValue == null || !protectedValue.isBoolean())) throw invalid();
            boolean protectedSlot = protectedValue != null && protectedValue.asBoolean();
            protectedSlot |= CharacterAnalysisStateMapper.protectedSlots(owner).stream()
                    .anyMatch(path -> path.factType() == type && key.equals(path.factKey()));
            result.add(new CurrentSlot(type, key, value, values.convertValue(valueJson, Object.class),
                    protectedSlot, sourceTimes, supporting));
        }
        JsonNode currentTime = owner.path("currentNarrativeTime");
        if (currentTime.isMissingNode() || currentTime.isNull()) currentTime = com.fasterxml.jackson.databind.node.NullNode.instance;
        else {
            exact(currentTime, Set.of("description", "evidenceSpans", "sourceCandidateId", "supportingSource"));
            var point = ((com.fasterxml.jackson.databind.node.ObjectNode) currentTime.deepCopy());
            point.remove(List.of("sourceCandidateId", "supportingSource"));
            if ("VERIFIED".equals(currentTime.path("supportingSource").path("availability").asText())) {
                org.monitoring.catchholebackend.domain.character.processor.CharacterNarrativeTime.requirePoint(point);
            } else if (!point.path("description").isTextual() || !point.path("evidenceSpans").isArray()) throw invalid();
            UUID id = uuid(currentTime.get("sourceCandidateId"), false);
            var frozen = (com.fasterxml.jackson.databind.node.ObjectNode) currentTime.deepCopy();
            frozen.set("supportingSource", safeOrigin(currentTime.get("supportingSource"), id));
            currentTime = frozen;
        }
        List<Object> history = new ArrayList<>();
        for (JsonNode fact : owner.path("historyFacts")) {
            exact(fact, Set.of("factId", "sourceCandidateId", "factType", "factKey", "factValue", "valueJson", "operation", "temporalScope",
                    "removedSnapshotEntries", "narrativeTime", "supportingSource"));
            uuid(fact.get("factId"), true);
            UUID id = uuid(fact.get("sourceCandidateId"), true);
            enumeration(CharacterFactType.class, text(fact, "factType", false));
            text(fact, "factKey", false);
            text(fact, "factValue", true);
            requireJson(fact.get("valueJson"));
            String operation = text(fact, "operation", true), temporal = text(fact, "temporalScope", true);
            if (operation != null) enumeration(org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation.class, operation);
            if (temporal != null) enumeration(org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope.class, temporal);
            if (!fact.path("removedSnapshotEntries").isArray()) throw invalid();
            for (JsonNode removal : fact.path("removedSnapshotEntries")) {
                exact(removal, Set.of("factType", "factKey")); enumeration(CharacterFactType.class, text(removal, "factType", false)); text(removal, "factKey", false);
            }
            validateNarrativeTime(fact.get("narrativeTime"));
            var frozen = (com.fasterxml.jackson.databind.node.ObjectNode) fact.deepCopy();
            frozen.set("supportingSource", safeOrigin(fact.get("supportingSource"), id));
            history.add(values.convertValue(frozen, Object.class));
            if (history.size() > 4096) throw invalid();
        }
        var semanticState = (com.fasterxml.jackson.databind.node.ObjectNode) owner.deepCopy();
        semanticState.remove("snapshotVersion");
        return new WorkerCharacterAdvisoryCurrentContextPayload(1, version, job.getAnalysisRunId(), job.getRunGeneration() == null ? 0 : job.getRunGeneration(),
                job.getInputStateHash(), job.getSourceContentHash(), journal.hash(semanticState), true, actual, provisional, List.copyOf(result), values.convertValue(currentTime, Object.class), List.copyOf(history));
    }

    private List<SupportingOrigin> origins(JsonNode slot, Set<UUID> expected) {
        JsonNode rows = required(slot, "supportingSources");
        if (!rows.isArray() || rows.isEmpty()) throw invalid();
        List<SupportingOrigin> result = new ArrayList<>();
        Set<UUID> found = new HashSet<>();
        for (JsonNode row : rows) {
            exact(row, Set.of("sourceCandidateId", "sourceAnalysisJobId", "availability", "unavailableReason",
                    "candidateWasEdited", "source", "evidenceSpans", "quoteRelation"), Set.of("storageBinding", "narrativeTime"));
            UUID candidate = uuid(row.get("sourceCandidateId"), true), job = uuid(row.get("sourceAnalysisJobId"), true);
            if (candidate != null && !found.add(candidate)) throw invalid();
            String availability = text(row, "availability", false), reason = text(row, "unavailableReason", true);
            String relation = text(row, "quoteRelation", false);
            if (!"SOURCE_CONTEXT_ONLY".equals(relation)) throw invalid();
            JsonNode edited = required(row, "candidateWasEdited");
            if (!edited.isBoolean() && !edited.isNull()) throw invalid();
            SupportingSource source = row.get("source").isNull() ? null : source(row.get("source"));
            JsonNode evidence = row.get("evidenceSpans");
            if (!evidence.isArray()) throw invalid();
            List<EvidenceSpan> evidenceSpans = new ArrayList<>();
            for (JsonNode span : evidence) {
                exact(span, Set.of("quote", "startOffset", "endOffset"));
                String quote = text(span, "quote", false);
                Integer start = integer(span, "startOffset"), end = integer(span, "endOffset");
                if ((start == null) != (end == null) || start != null && (start < 0 || end <= start)) throw invalid();
                evidenceSpans.add(new EvidenceSpan(quote, start, end));
            }
            if ("VERIFIED".equals(availability)) {
                if (reason != null || evidenceSpans.isEmpty()) throw invalid();
            } else if (!"UNAVAILABLE".equals(availability) || !REASONS.contains(reason) || !evidenceSpans.isEmpty()) throw invalid();
            if ("VERIFIED".equals(availability) || "QUOTE_NOT_FOUND".equals(reason)) {
                if (candidate == null || job == null || source == null) throw invalid();
                JsonNode binding = required(row, "storageBinding");
                exact(binding, Set.of("key", "versionId")); text(binding, "key", false); text(binding, "versionId", true);
            }
            JsonNode narrative = row.get("narrativeTime");
            validateNarrativeTime(narrative);
            result.add(new SupportingOrigin(candidate, job, availability, reason, edited.isNull() ? null : edited.booleanValue(),
                    source, List.copyOf(evidenceSpans), relation, values.convertValue(narrative, Object.class)));
        }
        if (!found.equals(expected)) throw invalid();
        return List.copyOf(result);
    }

    private JsonNode safeOrigin(JsonNode origin, UUID candidate) {
        var wrapper = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        wrapper.putArray("supportingSources").add(origin);
        return values.valueToTree(origins(wrapper, candidate == null ? Set.of() : Set.of(candidate)).getFirst());
    }

    private void validateNarrativeTime(JsonNode time) {
        if (time == null || time.isNull()) return;
        exact(time, Set.of("factTime", "referenceTime"));
        for (String field : List.of("factTime", "referenceTime")) if (!time.get(field).isNull()) {
            org.monitoring.catchholebackend.domain.character.processor.CharacterNarrativeTime.requirePoint(time.get(field));
        }
    }

    private SupportingSource source(JsonNode source) {
        exact(source, Set.of("sourceKind", "episodeId", "episodeNo", "settingBookId", "revisionId", "revisionNo",
                "contentHash", "referenceTimeKind", "referenceEpisodeNo"));
        AnalysisSourceKind kind = enumeration(AnalysisSourceKind.class, text(source, "sourceKind", false));
        UUID episode = uuid(source.get("episodeId"), true), book = uuid(source.get("settingBookId"), true), revision = uuid(source.get("revisionId"), true);
        Integer episodeNo = integer(source, "episodeNo"), revisionNo = integer(source, "revisionNo"), referenceNo = integer(source, "referenceEpisodeNo");
        String hash = text(source, "contentHash", false), reference = text(source, "referenceTimeKind", true);
        if (!hash(hash)) throw invalid();
        SettingBookReferenceTimeKind referenceKind = reference == null ? null : enumeration(SettingBookReferenceTimeKind.class, reference);
        if (kind == AnalysisSourceKind.EPISODE) {
            if (episode == null || episodeNo == null || episodeNo < 1 || book != null || revision != null
                    || revisionNo != null || referenceKind != null || referenceNo != null) throw invalid();
        } else if (episode != null || episodeNo != null || book == null || revision == null || revisionNo == null || revisionNo < 1
                || referenceKind == null || referenceKind == SettingBookReferenceTimeKind.EPISODE && (referenceNo == null || referenceNo < 1)
                || referenceKind != SettingBookReferenceTimeKind.EPISODE && referenceNo != null) throw invalid();
        return new SupportingSource(kind, episode, episodeNo, book, revision, revisionNo, hash, referenceKind, referenceNo);
    }

    private List<SupportingOrigin> legacy(Set<UUID> ids) {
        if (ids.isEmpty()) return List.of(legacyOrigin(null));
        return ids.stream().map(this::legacyOrigin).toList();
    }
    private SupportingOrigin legacyOrigin(UUID id) {
        return new SupportingOrigin(id, null, "UNAVAILABLE", "LEGACY_INPUT_WITHOUT_FROZEN_EVIDENCE", null, null, List.of(), "SOURCE_CONTEXT_ONLY");
    }
    private Set<UUID> sourceIds(JsonNode provenance, int version) {
        JsonNode ids = provenance.get("sourceCandidateIds");
        if (ids == null && version == 0) return Set.of();
        if (ids == null || !ids.isArray()) throw invalid();
        Set<UUID> result = new java.util.LinkedHashSet<>();
        for (JsonNode id : ids) if (!result.add(uuid(id, false))) throw invalid();
        return result;
    }
    private JsonNode required(JsonNode node, String field) {
        if (node == null || !node.isObject() || !node.has(field)) throw invalid();
        return node.get(field);
    }
    private String text(JsonNode node, String field, boolean nullable) {
        JsonNode value = required(node, field);
        if (nullable && value.isNull()) return null;
        if (!value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue();
    }
    private Integer integer(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
        return value.intValue();
    }
    private UUID uuid(JsonNode value, boolean nullable) {
        if (nullable && value != null && value.isNull()) return null;
        if (value == null || !value.isTextual()) throw invalid();
        return parseUuid(value.textValue());
    }
    private UUID parseUuid(String value) { try { return UUID.fromString(value); } catch (IllegalArgumentException exception) { throw invalid(); } }
    private <E extends Enum<E>> E enumeration(Class<E> type, String value) { try { return Enum.valueOf(type, value); } catch (IllegalArgumentException exception) { throw invalid(); } }
    private void exact(JsonNode node, Set<String> required) { exact(node, required, Set.of()); }
    private void exact(JsonNode node, Set<String> required, Set<String> optional) {
        if (node == null || !node.isObject()) throw invalid();
        for (String key : required) if (!node.has(key)) throw invalid();
        for (String key : keys(node)) if (!required.contains(key) && !optional.contains(key)) throw invalid();
    }
    private List<String> keys(JsonNode object) { List<String> result = new ArrayList<>(); object.fieldNames().forEachRemaining(result::add); result.sort(String::compareTo); return result; }
    private void requireJson(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isPojo() || node.isBinary()
                || node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue())) throw invalid();
        node.forEach(this::requireJson);
    }
    private boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private InvalidEvidenceException invalid() { return new InvalidEvidenceException(); }
}
