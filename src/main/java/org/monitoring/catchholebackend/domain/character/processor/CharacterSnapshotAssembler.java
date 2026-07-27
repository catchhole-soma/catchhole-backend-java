package org.monitoring.catchholebackend.domain.character.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.springframework.stereotype.Component;

/**
 * 호출자가 제공한 current Fact만 유형별 nullable object map으로 조립합니다.
 * 각 entry에는 raw valueJson을 그대로 넣고 deep merge하지 않으며, 비어 있는 그룹은 null로 반환합니다.
 */
@Component
public class CharacterSnapshotAssembler {

    public CharacterSnapshot assemble(List<CharacterFact> currentFacts) {
        Integer currentAge = null;
        Integer currentLevel = null;
        ObjectNode profileJson = JsonNodeFactory.instance.objectNode();
        ObjectNode statsJson = JsonNodeFactory.instance.objectNode();
        ObjectNode skillsJson = JsonNodeFactory.instance.objectNode();
        ObjectNode itemsJson = JsonNodeFactory.instance.objectNode();
        ObjectNode statusesJson = JsonNodeFactory.instance.objectNode();

        for (CharacterFact fact : currentFacts) {
            switch (fact.getFactType()) {
                case PROFILE -> put(profileJson, fact);
                case AGE -> currentAge = resolveInteger(fact);
                case LEVEL -> currentLevel = resolveInteger(fact);
                case STAT -> put(statsJson, fact);
                case SKILL -> put(skillsJson, fact);
                case ITEM -> put(itemsJson, fact);
                case STATUS, TIME -> put(statusesJson, fact);
            }
        }

        return new CharacterSnapshot(
                currentAge,
                currentLevel,
                nullIfEmpty(profileJson),
                nullIfEmpty(statsJson),
                nullIfEmpty(skillsJson),
                nullIfEmpty(itemsJson),
                nullIfEmpty(statusesJson)
        );
    }

    private void put(ObjectNode snapshot, CharacterFact fact) {
        snapshot.set(fact.getFactKey(), fact.getValueJson());
    }

    private Integer resolveInteger(CharacterFact fact) {
        JsonNode valueNode = fact.getValueJson();
        if (valueNode != null && valueNode.isObject()) {
            valueNode = valueNode.get("value");
        }
        if (valueNode != null && valueNode.isNumber()) {
            if (valueNode.canConvertToInt()
                    && valueNode.decimalValue().stripTrailingZeros().scale() <= 0) {
                return valueNode.asInt();
            }
            return null;
        }
        String value = fact.getFactValue();
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private JsonNode nullIfEmpty(ObjectNode snapshot) {
        return snapshot.isEmpty() ? null : snapshot;
    }
}
