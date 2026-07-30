package org.monitoring.catchholebackend.domain.character.processor;

import java.util.List;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.springframework.stereotype.Component;

/**
 * 캐릭터 상세 설정의 key가 exact schema, 동적 pattern, 수동 custom 중 어디에 속하는지 해석한다.
 */
@Component
public class CharacterSettingEditPolicyResolver {

    public CharacterSettingEditPolicy resolve(
            CharacterFactType factType,
            String factKey,
            List<CharacterSettingSchema> schemas
    ) {
        CharacterSettingSchema exactSchema = schemas.stream()
                .filter(schema -> schema.getFactType() == factType)
                .filter(schema -> schema.getSchemaKey().trim().equals(factKey))
                .findFirst()
                .orElse(null);
        if (exactSchema != null) {
            return CharacterSettingEditPolicy.exact(exactSchema);
        }

        CharacterSettingSchema patternSchema = schemas.stream()
                .filter(schema -> schema.getFactType() == factType)
                .filter(schema -> matchesPattern(schema.getAttributePattern(), factKey))
                .findFirst()
                .orElse(null);
        if (patternSchema == null || isManualKey(factKey)) {
            return CharacterSettingEditPolicy.custom(patternSchema);
        }
        return CharacterSettingEditPolicy.pattern(patternSchema, patternPrefix(patternSchema));
    }

    private boolean matchesPattern(String pattern, String factKey) {
        if (pattern == null || !pattern.trim().endsWith(".*")) {
            return false;
        }
        String normalizedPattern = pattern.trim();
        String prefix = normalizedPattern.substring(0, normalizedPattern.length() - 1);
        return factKey.startsWith(prefix) && factKey.length() > prefix.length();
    }

    private String patternPrefix(CharacterSettingSchema schema) {
        String pattern = schema.getAttributePattern().trim();
        return pattern.substring(0, pattern.length() - 1);
    }

    private boolean isManualKey(String factKey) {
        int separatorIndex = factKey.lastIndexOf('.');
        return separatorIndex >= 0 && factKey.substring(separatorIndex + 1).startsWith("manual_");
    }

    public enum CharacterSettingEditType {
        EXACT,
        PATTERN,
        CUSTOM
    }

    public record CharacterSettingEditPolicy(
            CharacterSettingEditType type,
            CharacterSettingSchema schema,
            String attributeNamePrefix
    ) {

        private static CharacterSettingEditPolicy exact(CharacterSettingSchema schema) {
            return new CharacterSettingEditPolicy(CharacterSettingEditType.EXACT, schema, null);
        }

        private static CharacterSettingEditPolicy pattern(
                CharacterSettingSchema schema,
                String attributeNamePrefix
        ) {
            return new CharacterSettingEditPolicy(
                    CharacterSettingEditType.PATTERN,
                    schema,
                    attributeNamePrefix
            );
        }

        private static CharacterSettingEditPolicy custom(CharacterSettingSchema schema) {
            return new CharacterSettingEditPolicy(CharacterSettingEditType.CUSTOM, schema, null);
        }

        public boolean attributeNameEditable() {
            return type == CharacterSettingEditType.PATTERN;
        }

        public boolean displayNameEditable() {
            return type != CharacterSettingEditType.EXACT;
        }
    }
}
