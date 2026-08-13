package org.monitoring.catchholebackend.domain.character.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.StreamSupport;
import org.monitoring.catchholebackend.domain.character.dto.response.WorkerCharacterFactComparisonCandidatePayload;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.springframework.stereotype.Component;

@Component
public class CharacterFactComparisonWorkerMapper {

    private final ObjectMapper objectMapper = new ObjectMapper();

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
                toEvidenceSpans(candidate.getEvidenceSpans()),
                candidate.getConfidence(),
                candidate.getMatchedCharacterId(),
                matchedCharacterName,
                canonicalFactType,
                canonicalFactKey
        );
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
                        integerValue(span, "startOffset"),
                        integerValue(span, "endOffset")
                ))
                .toList();
    }

    private String textValue(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Integer integerValue(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || !value.isIntegralNumber() ? null : value.asInt();
    }
}
