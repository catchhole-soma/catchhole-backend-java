package org.monitoring.catchholebackend.domain.character.processor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactCanonicalKeyResolution;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;

@Component
public class SettingCandidateSchemaResolver {

    public SettingCandidateSchemaMatch resolve(
            String attributeName,
            SettingValueType candidateValueType,
            List<CharacterSettingSchema> schemas
    ) {
        String trimmedAttributeName = attributeName.trim();

        // 전체 설정 정의 중 schemaKey가 후보 속성명과 정확히 같은 항목부터 찾는다.
        // 하나라도 찾으면 별칭이나 속성 패턴은 검사하지 않는다.
        List<CharacterSettingSchema> exactMatches = schemas.stream()
                .filter(schema -> schema.getSchemaKey().trim().equals(trimmedAttributeName))
                .toList();
        if (!exactMatches.isEmpty()) {
            return resolveUnique(
                    exactMatches,
                    candidateValueType,
                    trimmedAttributeName,
                    false,
                    CharacterFactCanonicalKeyResolution.EXACT
            );
        }

        // 별칭은 단독 값 또는 schemaKey와 같은 분류 경로가 붙은 값만 허용한다.
        // 예: 힘, stats.힘은 허용하지만 profile.힘은 허용하지 않는다.
        List<CharacterSettingSchema> aliasMatches = schemas.stream()
                .filter(schema -> matchesAlias(schema, trimmedAttributeName))
                .toList();
        if (!aliasMatches.isEmpty()) {
            return resolveUnique(
                    aliasMatches,
                    candidateValueType,
                    trimmedAttributeName,
                    false,
                    CharacterFactCanonicalKeyResolution.ALIAS
            );
        }

        // 이름이 매번 달라지는 속성은 마지막이 .*로 끝나는 패턴만 허용한다.
        // 예: skill.*은 skill.검술과 일치하지만 skill.처럼 점 뒤에 이름이 없으면 거절한다.
        List<CharacterSettingSchema> patternMatches = schemas.stream()
                .filter(schema -> matchesTrailingWildcard(schema.getAttributePattern(), trimmedAttributeName))
                .toList();
        if (!patternMatches.isEmpty()) {
            return resolveUnique(
                    patternMatches,
                    candidateValueType,
                    trimmedAttributeName,
                    true,
                    CharacterFactCanonicalKeyResolution.PATTERN
            );
        }

        throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_SCHEMA_NOT_MATCHED);
    }

    private boolean matchesAlias(CharacterSettingSchema schema, String attributeName) {
        JsonNode aliases = schema.getAliasesJson();
        if (aliases == null || !aliases.isArray()) {
            return false;
        }

        String namespace = schemaNamespace(schema.getSchemaKey().trim());
        for (JsonNode aliasNode : aliases) {
            if (!aliasNode.isTextual()) {
                continue;
            }
            String alias = aliasNode.asText().trim();
            // 별칭에는 분류 경로를 저장하지 않고, 아래에서 schemaKey의 분류 경로를 붙여 비교한다.
            if (alias.isEmpty() || alias.contains(".")) {
                continue;
            }
            if (attributeName.equals(alias)
                    || (!namespace.isEmpty() && attributeName.equals(namespace + alias))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesTrailingWildcard(String attributePattern, String attributeName) {
        if (attributePattern == null) {
            return false;
        }
        String pattern = attributePattern.trim();
        int wildcardIndex = pattern.indexOf('*');
        if (!pattern.endsWith(".*") || wildcardIndex != pattern.length() - 1) {
            return false;
        }

        String prefix = pattern.substring(0, wildcardIndex);
        return attributeName.startsWith(prefix) && attributeName.length() > prefix.length();
    }

    private SettingCandidateSchemaMatch resolveUnique(
            List<CharacterSettingSchema> matches,
            SettingValueType candidateValueType,
            String trimmedAttributeName,
            boolean preserveAttributeName,
            CharacterFactCanonicalKeyResolution canonicalKeyResolution
    ) {
        // 같은 검사 단계에서 여러 설정 정의가 일치하면 정렬 순서로 임의 선택하지 않는다.
        if (matches.size() > 1) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_SCHEMA_MATCH_AMBIGUOUS);
        }

        CharacterSettingSchema matchedSchema = matches.getFirst();
        if (candidateValueType != matchedSchema.getValueType()) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_TYPE_MISMATCH);
        }

        // schemaKey와 정확히 일치하거나 별칭으로 찾은 경우에는 해당 schemaKey를 최종 저장 키로 사용한다.
        // 속성 패턴으로 찾은 경우에는 개별 대상을 구분하기 위해 후보 속성명을 최종 저장 키로 사용한다.
        String factKey = preserveAttributeName
                ? trimmedAttributeName
                : matchedSchema.getSchemaKey().trim();
        return new SettingCandidateSchemaMatch(matchedSchema, factKey, canonicalKeyResolution);
    }

    private String schemaNamespace(String schemaKey) {
        int lastSeparatorIndex = schemaKey.lastIndexOf('.');
        return lastSeparatorIndex < 0 ? "" : schemaKey.substring(0, lastSeparatorIndex + 1);
    }
}
