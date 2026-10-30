package org.monitoring.catchholebackend.domain.character.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.StreamSupport;
import org.monitoring.catchholebackend.domain.character.dto.response.WorkerCharacterFactComparisonCandidatePayload;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.processor.GeneralEvidenceProjection;

@Component
@RequiredArgsConstructor
public class CharacterFactComparisonWorkerMapper {

    private final GeneralEvidenceProjection evidenceProjection;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CharacterAdvisoryCurrentContextMapper advisoryMapper = new CharacterAdvisoryCurrentContextMapper();

    public org.monitoring.catchholebackend.domain.character.dto.response.WorkerCharacterAdvisoryCurrentContextPayload toAdvisoryCurrentContext(
            org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob job, JsonNode state,
            java.util.UUID actualCharacterId, String provisionalSubjectKey) {
        return advisoryMapper.toResponse(job, state, actualCharacterId, provisionalSubjectKey);
    }

    public org.monitoring.catchholebackend.domain.character.dto.response.WorkerCharacterAdvisoryCurrentContextPayload toAdvisoryCurrentContext(
            org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob job, JsonNode state,
            java.util.UUID actualCharacterId, String provisionalSubjectKey, CharacterFactType factType) {
        com.fasterxml.jackson.databind.node.ObjectNode selected = state.deepCopy();
        var history = selected.putArray("historyFacts");
        for (JsonNode fact : state.path("historyFacts")) if (factType == null || factType.name().equals(fact.path("factType").asText())) history.add(fact.deepCopy());
        return advisoryMapper.toResponse(job, selected, actualCharacterId, provisionalSubjectKey);
    }

    public WorkerCharacterFactComparisonCandidatePayload toCandidatePayload(
            SettingCandidate candidate,
            String matchedCharacterName,
            CharacterFactType canonicalFactType,
            String canonicalFactKey
    ) {
        return new WorkerCharacterFactComparisonCandidatePayload(
                candidate.getId(),
                candidate.getWork().getId(),
                candidate.getEpisode() == null ? null : candidate.getEpisode().getId(),
                candidate.getEntityName(),
                candidate.getAttributeName(),
                candidate.getAttributeValue(),
                candidate.getValueType(),
                toJsonValue(candidate.getValueJson()),
                toCandidateEvidenceSpans(candidate),
                candidate.getConfidence(),
                candidate.getMatchedCharacterId(),
                matchedCharacterName,
                canonicalFactType,
                canonicalFactKey, org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisSourceResponse.from(candidate.getAnalysisJob()), toJsonValue(candidate.getNarrativeTime()));
    }

    public JsonNode toJsonNode(Object value) {
        return value == null ? null : objectMapper.valueToTree(value);
    }

    public Object toJsonValue(JsonNode value) {
        return value == null ? null : objectMapper.convertValue(value, Object.class);
    }

    public List<WorkerCharacterFactComparisonCandidatePayload.EvidenceSpan> toEvidenceSpans(JsonNode value) {
        if (value == null || !value.isArray()) {
            return List.of();
        }
        return StreamSupport.stream(value.spliterator(), false)
                .filter(JsonNode::isObject)
                .map(span -> new WorkerCharacterFactComparisonCandidatePayload.EvidenceSpan(
                        textValue(span, "quote"),
                        integerValue(span, "startOffset", "start_offset"),
                        integerValue(span, "endOffset", "end_offset")
                ))
                .toList();
    }

    public List<WorkerCharacterFactComparisonCandidatePayload.EvidenceSpan> toCandidateEvidenceSpans(SettingCandidate candidate) {
        return toEvidenceSpans(evidenceProjection.forProcessingCandidate(candidate));
    }

    private String textValue(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Integer integerValue(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isIntegralNumber() && value.canConvertToInt()) {
                return value.intValue();
            }
        }
        return null;
    }
}
