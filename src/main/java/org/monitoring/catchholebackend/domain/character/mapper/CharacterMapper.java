package org.monitoring.catchholebackend.domain.character.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterArchiveResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterDetailResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterEpisodeResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterFactReferenceResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterSettingPropertyResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterSettingResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterSummaryResponse;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.springframework.stereotype.Component;

@Component
public class CharacterMapper {

    public CharacterSummaryResponse toSummaryResponse(WorkCharacter character, Integer firstAppearanceEpisodeNo) {
        Integer currentLevel = character.getCurrentLevel();
        return new CharacterSummaryResponse(
                character.getId(),
                character.getName(),
                character.getCurrentAge(),
                currentLevel == null ? null : "레벨",
                currentLevel == null ? null : currentLevel.toString(),
                firstAppearanceEpisodeNo
        );
    }

    public List<CharacterSummaryResponse> toSummaryResponseList(
            List<WorkCharacter> characters,
            Map<UUID, Integer> firstAppearanceEpisodeNosById
    ) {
        return characters.stream()
                .map(character -> {
                    UUID firstAppearanceEpisodeId = character.getFirstAppearanceEpisodeId();
                    Integer firstAppearanceEpisodeNo = firstAppearanceEpisodeId == null
                            ? null
                            : firstAppearanceEpisodeNosById.get(firstAppearanceEpisodeId);
                    return toSummaryResponse(character, firstAppearanceEpisodeNo);
                })
                .toList();
    }

    public CharacterDetailResponse toDetailResponse(
            WorkCharacter character,
            Episode firstAppearanceEpisode,
            List<CharacterFact> currentFacts,
            List<CharacterSettingSchema> schemas
    ) {
        return new CharacterDetailResponse(
                character.getId(),
                character.getName(),
                character.getRoleLabel(),
                character.getCurrentAge(),
                toFactReferenceResponse(currentFacts, CharacterFactType.AGE),
                character.getCurrentLevel(),
                toFactReferenceResponse(currentFacts, CharacterFactType.LEVEL),
                toEpisodeResponse(firstAppearanceEpisode),
                toSettingResponses(currentFacts, schemas, CharacterFactType.PROFILE),
                toSettingResponses(currentFacts, schemas, CharacterFactType.STAT),
                toSettingResponses(currentFacts, schemas, CharacterFactType.SKILL),
                toSettingResponses(currentFacts, schemas, CharacterFactType.ITEM),
                toSettingResponses(currentFacts, schemas, CharacterFactType.STATUS)
        );
    }

    public CharacterArchiveResponse toArchiveResponse(WorkCharacter character) {
        return new CharacterArchiveResponse(character.getId(), character.getStatus());
    }

    public CharacterFact toManualFact(
            WorkCharacter character,
            CharacterFactType factType,
            String factKey,
            String factValue,
            JsonNode valueJson
    ) {
        return CharacterFact.createManual(character, factType, factKey, factValue, valueJson);
    }

    private CharacterEpisodeResponse toEpisodeResponse(Episode episode) {
        if (episode == null) {
            return null;
        }
        return new CharacterEpisodeResponse(episode.getId(), episode.getEpisodeNo());
    }

    private CharacterFactReferenceResponse toFactReferenceResponse(
            List<CharacterFact> currentFacts,
            CharacterFactType factType
    ) {
        return currentFacts.stream()
                .filter(fact -> fact.getFactType() == factType)
                .findFirst()
                .map(fact -> new CharacterFactReferenceResponse(
                        fact.getId(),
                        hasEvidence(fact.getSettingCandidate())
                ))
                .orElse(null);
    }

    private List<CharacterSettingResponse> toSettingResponses(
            List<CharacterFact> currentFacts,
            List<CharacterSettingSchema> schemas,
            CharacterFactType factType
    ) {
        return currentFacts.stream()
                .filter(fact -> fact.getFactType() == factType)
                .map(fact -> toSettingResponse(fact, schemas))
                .toList();
    }

    private CharacterSettingResponse toSettingResponse(
            CharacterFact fact,
            List<CharacterSettingSchema> schemas
    ) {
        CharacterSettingSchema schema = findSchema(fact.getFactKey(), schemas);
        JsonNode valueJson = fact.getValueJson();
        return new CharacterSettingResponse(
                fact.getId(),
                fact.getFactKey(),
                resolveDisplayName(fact.getFactKey(), valueJson, schema),
                fact.getFactValue(),
                schema == null ? inferValueType(resolvePrimaryValue(valueJson)) : schema.getValueType(),
                toProperties(valueJson),
                hasEvidence(fact.getSettingCandidate())
        );
    }

    private CharacterSettingSchema findSchema(String factKey, List<CharacterSettingSchema> schemas) {
        for (CharacterSettingSchema schema : schemas) {
            if (schema.getSchemaKey().equals(factKey)) {
                return schema;
            }
        }
        for (CharacterSettingSchema schema : schemas) {
            if (matchesPattern(schema.getAttributePattern(), factKey)) {
                return schema;
            }
        }
        return null;
    }

    private boolean matchesPattern(String pattern, String factKey) {
        if (pattern == null || !pattern.endsWith(".*")) {
            return false;
        }
        String prefix = pattern.substring(0, pattern.length() - 1);
        return factKey.startsWith(prefix) && factKey.length() > prefix.length();
    }

    private String resolveDisplayName(
            String factKey,
            JsonNode valueJson,
            CharacterSettingSchema schema
    ) {
        if (valueJson != null && valueJson.isObject()) {
            JsonNode nameNode = valueJson.get("name");
            if (nameNode != null && nameNode.isTextual() && !nameNode.asText().isBlank()) {
                return nameNode.asText();
            }
        }
        if (schema != null && schema.getSchemaKey().equals(factKey)) {
            return schema.getDisplayName();
        }
        int separatorIndex = factKey.lastIndexOf('.');
        return separatorIndex < 0 ? factKey : factKey.substring(separatorIndex + 1);
    }

    private List<CharacterSettingPropertyResponse> toProperties(JsonNode valueJson) {
        if (valueJson == null || !valueJson.isObject()) {
            return List.of();
        }
        List<CharacterSettingPropertyResponse> properties = new ArrayList<>();
        valueJson.properties().forEach(entry -> {
            if (!entry.getKey().equals("value")) {
                properties.add(new CharacterSettingPropertyResponse(
                        entry.getKey(),
                        toPropertyDisplayName(entry.getKey()),
                        toDisplayValue(entry.getValue()),
                        inferValueType(entry.getValue())
                ));
            }
        });
        return List.copyOf(properties);
    }

    private JsonNode resolvePrimaryValue(JsonNode valueJson) {
        if (valueJson == null) {
            return null;
        }
        return valueJson.isObject() ? valueJson.get("value") : valueJson;
    }

    private boolean hasEvidence(SettingCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        JsonNode evidenceSpans = candidate.getEvidenceSpans();
        return evidenceSpans != null && evidenceSpans.isArray() && !evidenceSpans.isEmpty();
    }

    private SettingValueType inferValueType(JsonNode valueNode) {
        if (valueNode == null || valueNode.isNull()) {
            return SettingValueType.UNKNOWN;
        }
        if (valueNode.isNumber()) {
            return SettingValueType.NUMBER;
        }
        if (valueNode.isBoolean()) {
            return SettingValueType.BOOLEAN;
        }
        if (valueNode.isContainerNode()) {
            return SettingValueType.JSON;
        }
        return SettingValueType.STRING;
    }

    private String toDisplayValue(JsonNode valueNode) {
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }
        return valueNode.isValueNode() ? valueNode.asText() : valueNode.toString();
    }

    private String toPropertyDisplayName(String key) {
        return switch (key) {
            case "name" -> "이름";
            case "level" -> "레벨";
            case "quantity" -> "수량";
            case "state" -> "상태";
            case "active" -> "활성 여부";
            case "description" -> "설명";
            default -> key;
        };
    }
}
