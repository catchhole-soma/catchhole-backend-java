package org.monitoring.catchholebackend.domain.character.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;

/** 사용자 후보와 AI 제안값에 같은 snapshot 저장 계약을 적용한다. */
@Component
public class CharacterSettingValueValidator {

    private static final int MAX_PROPERTY_KEY_LENGTH = 100;

    public void validateCandidate(
            SettingCandidate candidate,
            CharacterFactType factType,
            SettingValueType schemaValueType
    ) {
        validateCoreValue(candidate.getValueJson(), candidate.getAttributeValue(), factType, true);
        validateStructuredProperties(candidate.getValueJson(), factType, schemaValueType);
    }

    public void validateProposal(
            JsonNode proposedValueJson,
            CharacterFactType factType,
            SettingValueType schemaValueType
    ) {
        if (proposedValueJson == null || proposedValueJson.isNull()) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_JSON_INVALID);
        }
        validateCoreValue(proposedValueJson, null, factType, false);
        validateStructuredProperties(proposedValueJson, factType, schemaValueType);
        if (schemaValueType != SettingValueType.JSON
                && (!proposedValueJson.isObject()
                || !hasCompatibleScalarEnvelope(proposedValueJson, schemaValueType))) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_JSON_INVALID);
        }
    }

    /**
     * value_json 도입 전 생성된 scalar 후보도 새 캐릭터의 최초 snapshot에 안전하게 반영한다.
     * Fact와 snapshot이 서로 다른 모양을 갖지 않도록 호출자가 이 정규화 값을 양쪽에 함께 사용한다.
     */
    public JsonNode resolveCandidateValue(
            SettingCandidate candidate,
            CharacterFactType factType,
            SettingValueType schemaValueType
    ) {
        JsonNode valueJson = candidate.getValueJson();
        if (valueJson != null && !valueJson.isNull()) {
            return valueJson;
        }
        String displayValue = candidate.getAttributeValue();
        if (displayValue == null || displayValue.isBlank()) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_JSON_INVALID);
        }
        String normalized = displayValue.trim();
        ObjectNode envelope = JsonNodeFactory.instance.objectNode();
        switch (schemaValueType) {
            case NUMBER -> {
                BigDecimal number = parseNumber(normalized);
                if (number == null) {
                    throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_JSON_INVALID);
                }
                envelope.put("value", number);
            }
            case BOOLEAN -> {
                if (!normalized.equalsIgnoreCase("true") && !normalized.equalsIgnoreCase("false")) {
                    throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_JSON_INVALID);
                }
                envelope.put("value", Boolean.parseBoolean(normalized));
            }
            case STRING, JSON, UNKNOWN -> envelope.put("value", normalized);
        }
        validateProposal(envelope, factType, schemaValueType);
        return envelope;
    }

    private void validateCoreValue(
            JsonNode valueJson,
            String fallbackDisplayValue,
            CharacterFactType factType,
            boolean allowFallback
    ) {
        if (factType != CharacterFactType.AGE && factType != CharacterFactType.LEVEL) {
            return;
        }
        BigDecimal value = resolveCoreNumber(valueJson);
        if (value == null && allowFallback) {
            value = parseNumber(fallbackDisplayValue);
        }
        if (value == null) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_INVALID);
        }
        try {
            if (value.intValueExact() < 0) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_INVALID);
            }
        } catch (ArithmeticException exception) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_INVALID);
        }
    }

    private void validateStructuredProperties(
            JsonNode valueJson,
            CharacterFactType factType,
            SettingValueType valueType
    ) {
        if (!hasEditableProperties(factType) || valueJson == null || !valueJson.isObject()) {
            return;
        }
        Set<String> keys = new HashSet<>();
        boolean hasPublicProperty = valueJson.size() > (valueJson.has("value") ? 1 : 0);
        valueJson.properties().forEach(entry -> {
            String rawKey = entry.getKey();
            if (rawKey.equals("value")) {
                return;
            }
            String key = rawKey.trim();
            JsonNode propertyValue = entry.getValue();
            boolean invalidTextValue = propertyValue.isTextual()
                    && (propertyValue.asText().isEmpty()
                    || !propertyValue.asText().equals(propertyValue.asText().trim()));
            if (rawKey.isBlank()
                    || rawKey.length() > MAX_PROPERTY_KEY_LENGTH
                    || !rawKey.equals(key)
                    || key.equals("value")
                    || !keys.add(key)
                    || invalidTextValue) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_JSON_INVALID);
            }
        });
        if (hasPublicProperty
                && valueType != SettingValueType.JSON
                && !hasCompatibleScalarEnvelope(valueJson, valueType)) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_JSON_INVALID);
        }
    }

    private boolean hasEditableProperties(CharacterFactType factType) {
        return switch (factType) {
            case PROFILE, STAT, SKILL, ITEM, STATUS -> true;
            case AGE, LEVEL, TIME -> false;
        };
    }

    private boolean hasCompatibleScalarEnvelope(JsonNode valueJson, SettingValueType valueType) {
        JsonNode valueNode = valueJson.get("value");
        if (valueNode == null) {
            return false;
        }
        if (valueType == SettingValueType.UNKNOWN) {
            return true;
        }
        if (valueNode.isNull()) {
            return false;
        }
        return switch (valueType) {
            case STRING -> valueNode.isTextual();
            case NUMBER -> valueNode.isNumber();
            case BOOLEAN -> valueNode.isBoolean();
            case JSON, UNKNOWN -> true;
        };
    }

    private BigDecimal resolveCoreNumber(JsonNode valueJson) {
        JsonNode valueNode = valueJson;
        if (valueNode != null && valueNode.isObject()) {
            valueNode = valueNode.get("value");
        }
        return valueNode != null && valueNode.isNumber() ? valueNode.decimalValue() : null;
    }

    private BigDecimal parseNumber(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
