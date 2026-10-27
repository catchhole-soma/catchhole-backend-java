package org.monitoring.catchholebackend.domain.character.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;

/** A selection of an existing grounded point, never a clock or date parser. */
public final class CharacterNarrativeTime {
    private CharacterNarrativeTime() {}

    public static JsonNode value(JsonNode time) {
        if (time == null || time.isNull() || time.isMissingNode()) return JsonNodeFactory.instance.nullNode();
        if (!time.isObject()) throw invalid();
        ObjectNode result = time.deepCopy();
        result.remove("supportingSource");
        return result;
    }

    public static void requirePoint(JsonNode point) {
        if (!point.isObject() || point.size() != 2 || !point.path("description").isTextual()
                || point.path("description").asText().isBlank() || !point.path("evidenceSpans").isArray()
                || point.path("evidenceSpans").isEmpty()) throw invalid();
        for (JsonNode span : point.path("evidenceSpans")) {
            if (!span.isObject() || span.size() != 4 || !span.path("quote").isTextual()
                    || span.path("quote").asText().isBlank() || !span.path("startOffset").isIntegralNumber()
                    || !span.path("startOffset").canConvertToInt() || !span.path("endOffset").isIntegralNumber()
                    || !span.path("endOffset").canConvertToInt() || span.path("startOffset").asInt() < 0
                    || span.path("endOffset").asInt() <= span.path("startOffset").asInt()
                    || !span.path("sourceChunkId").isTextual()) throw invalid();
            try { UUID.fromString(span.path("sourceChunkId").asText()); }
            catch (IllegalArgumentException exception) { throw invalid(); }
        }
    }

    public static JsonNode selection(SettingCandidate candidate, boolean advance, JsonNode current, JsonNode verifiedTime) {
        ObjectNode marker = JsonNodeFactory.instance.objectNode().put("advance", advance);
        marker.set("expectedCurrent", value(current));
        marker.putNull("selected");
        if (!advance) return marker;
        if (candidate.getTemporalScope() != CharacterFactTemporalScope.PRESENT
                || candidate.getSuggestedOperation() == null || candidate.getSuggestedOperation() == CharacterFactOperation.REVIEW_REQUIRED
                || candidate.getAutomaticReviewHoldReason() != null || verifiedTime == null
                || !verifiedTime.path("referenceTime").isObject()) throw invalid();
        // Source replacement removes the old point's quotes, not the author's confirmed state.
        // A newly grounded PRESENT observation can establish the next point; expectedCurrent
        // still prevents applying this choice after another selection changed the character.
        JsonNode point = verifiedTime.path("referenceTime");
        requirePoint(point);
        ObjectNode selected = point.deepCopy();
        selected.put("sourceCandidateId", candidate.getId().toString());
        marker.set("selected", selected);
        return marker;
    }

    public static JsonNode selected(SettingCandidate candidate, JsonNode current) {
        JsonNode raw = candidate.getRawComparisonJson();
        if (raw == null || candidate.getAutomaticReviewHoldReason() != null) return null;
        JsonNode marker = raw.path("validatedNarrativeTime");
        if (!marker.path("advance").asBoolean() || !marker.path("selected").isObject()
                || !Objects.equals(marker.path("expectedCurrent"), value(current))) return null;
        return marker.path("selected").deepCopy();
    }

    /** Order only validated time selections by their expected state, independent of timestamps or UUIDs. */
    public static java.util.List<SettingCandidate> replaySelections(java.util.List<SettingCandidate> candidates, JsonNode current) {
        var remaining = new java.util.ArrayList<>(candidates);
        var replayed = new java.util.ArrayList<SettingCandidate>();
        JsonNode time = current;
        // ponytail: linear rescans keep bounded comparison lists simple; index expected states if replay size grows.
        while (!remaining.isEmpty()) {
            boolean progressed = false;
            for (var iterator = remaining.iterator(); iterator.hasNext();) {
                SettingCandidate candidate = iterator.next();
                JsonNode next = selected(candidate, time);
                if (next == null) continue;
                replayed.add(candidate);
                time = next;
                iterator.remove();
                progressed = true;
                break;
            }
            if (!progressed) break;
        }
        return java.util.List.copyOf(replayed);
    }

    /** Keep descriptive/audit values while removing every copied source quote and position. */
    public static JsonNode purgeEvidence(JsonNode node) {
        if (node == null) return null;
        JsonNode copy = node.deepCopy();
        redact(copy);
        return copy;
    }

    private static void redact(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            object.remove(java.util.List.of("quote", "startOffset", "endOffset", "start_offset", "end_offset", "sourceChunkId", "storageBinding", "identityEvidence"));
            if (object.has("evidenceSpans")) object.putArray("evidenceSpans");
            if (object.has("evidence_spans")) object.putArray("evidence_spans");
            if (object.has("availability")) {
                object.put("availability", "UNAVAILABLE").put("unavailableReason", "SOURCE_EVIDENCE_PURGED");
            }
        }
        node.forEach(CharacterNarrativeTime::redact);
    }

    private static CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException invalid() {
        return new CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException();
    }
}
