package org.monitoring.catchholebackend.domain.character.processor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSettingEditPolicyResolver.CharacterSettingEditPolicy;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSettingEditPolicyResolver.CharacterSettingEditType;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.springframework.stereotype.Component;

/**
 * 내부 factKey와 저장 JSON을 캐릭터 설정 화면에서 공통으로 사용할 표시명으로 변환한다.
 */
@Component
@RequiredArgsConstructor
public class CharacterSettingDisplayNameResolver {

    private final CharacterSettingEditPolicyResolver characterSettingEditPolicyResolver;

    public String resolve(
            CharacterFactType factType,
            String factKey,
            JsonNode valueJson,
            List<CharacterSettingSchema> schemas
    ) {
        CharacterSettingEditPolicy editPolicy = characterSettingEditPolicyResolver.resolve(
                factType,
                factKey,
                schemas
        );
        return resolve(factKey, valueJson, editPolicy);
    }

    public String resolve(
            String factKey,
            JsonNode valueJson,
            CharacterSettingEditPolicy editPolicy
    ) {
        if (editPolicy.type() == CharacterSettingEditType.EXACT) {
            return editPolicy.schema().getDisplayName();
        }
        if (editPolicy.type() == CharacterSettingEditType.PATTERN) {
            return normalizeName(factKey.substring(editPolicy.attributeNamePrefix().length()));
        }
        if (valueJson != null && valueJson.isObject()) {
            JsonNode nameNode = valueJson.get("name");
            if (nameNode != null && nameNode.isTextual() && !nameNode.asText().isBlank()) {
                return nameNode.asText().trim();
            }
        }
        int separatorIndex = factKey.lastIndexOf('.');
        String suffix = separatorIndex < 0 ? factKey : factKey.substring(separatorIndex + 1);
        return normalizeName(suffix);
    }

    private String normalizeName(String name) {
        return name.replace('_', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
