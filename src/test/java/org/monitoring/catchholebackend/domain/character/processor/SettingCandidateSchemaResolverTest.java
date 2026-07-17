package org.monitoring.catchholebackend.domain.character.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingSchemaSource;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingValueSemantics;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.global.exception.AppException;

@DisplayName("설정 후보 Schema Resolver 테스트")
class SettingCandidateSchemaResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SettingCandidateSchemaResolver resolver = new SettingCandidateSchemaResolver();

    @Test
    @DisplayName("trim한 attributeName이 schemaKey와 같으면 exact 매칭한다")
    void resolveMatchesExactSchemaKey() {
        CharacterSettingSchema schema = schema(
                "stats.strength",
                null,
                CharacterFactType.STAT,
                SettingValueType.NUMBER,
                "힘"
        );

        SettingCandidateSchemaMatch match = resolver.resolve(
                "  stats.strength  ",
                SettingValueType.NUMBER,
                List.of(schema)
        );

        assertThat(match.matchedSchema()).isSameAs(schema);
        assertThat(match.factKey()).isEqualTo("stats.strength");
    }

    @Test
    @DisplayName("schema namespace 안의 alias를 canonical schemaKey로 매칭한다")
    void resolveMatchesNamespacedAlias() {
        CharacterSettingSchema schema = schema(
                "stats.strength",
                null,
                CharacterFactType.STAT,
                SettingValueType.NUMBER,
                "힘"
        );

        SettingCandidateSchemaMatch match = resolver.resolve(
                "stats.힘",
                SettingValueType.NUMBER,
                List.of(schema)
        );

        assertThat(match.matchedSchema()).isSameAs(schema);
        assertThat(match.factKey()).isEqualTo("stats.strength");
    }

    @Test
    @DisplayName("bare alias도 canonical schemaKey로 매칭한다")
    void resolveMatchesBareAlias() {
        CharacterSettingSchema schema = schema(
                "stats.strength",
                null,
                CharacterFactType.STAT,
                SettingValueType.NUMBER,
                "힘"
        );

        SettingCandidateSchemaMatch match = resolver.resolve(
                "힘",
                SettingValueType.NUMBER,
                List.of(schema)
        );

        assertThat(match.factKey()).isEqualTo("stats.strength");
    }

    @Test
    @DisplayName("다른 namespace의 같은 suffix는 alias로 매칭하지 않는다")
    void resolveRejectsAliasFromDifferentNamespace() {
        CharacterSettingSchema schema = schema(
                "stats.strength",
                null,
                CharacterFactType.STAT,
                SettingValueType.NUMBER,
                "힘"
        );

        assertThatThrownBy(() -> resolver.resolve(
                "profile.힘",
                SettingValueType.NUMBER,
                List.of(schema)
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_SCHEMA_NOT_MATCHED));
    }

    @Test
    @DisplayName("다른 분류 경로가 포함된 별칭 항목은 매칭하지 않는다")
    void resolveRejectsAliasEntryFromDifferentNamespace() {
        CharacterSettingSchema schema = schema(
                "stats.strength",
                null,
                CharacterFactType.STAT,
                SettingValueType.NUMBER,
                "profile.힘"
        );

        assertThatThrownBy(() -> resolver.resolve(
                "profile.힘",
                SettingValueType.NUMBER,
                List.of(schema)
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_SCHEMA_NOT_MATCHED));
    }

    @Test
    @DisplayName("trailing wildcard pattern은 원본 attributeName을 factKey로 유지한다")
    void resolveMatchesTrailingWildcardPattern() {
        CharacterSettingSchema schema = schema(
                "statuses.condition",
                "status.*",
                CharacterFactType.STATUS,
                SettingValueType.JSON
        );

        SettingCandidateSchemaMatch match = resolver.resolve(
                "  status.악령_깃들임  ",
                SettingValueType.JSON,
                List.of(schema)
        );

        assertThat(match.matchedSchema()).isSameAs(schema);
        assertThat(match.factKey()).isEqualTo("status.악령_깃들임");
    }

    @Test
    @DisplayName("trailing wildcard 앞 prefix만 있는 attributeName은 거절한다")
    void resolveRejectsEmptyPatternSuffix() {
        CharacterSettingSchema schema = schema(
                "statuses.condition",
                "status.*",
                CharacterFactType.STATUS,
                SettingValueType.JSON
        );

        assertThatThrownBy(() -> resolver.resolve(
                "status.",
                SettingValueType.JSON,
                List.of(schema)
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_SCHEMA_NOT_MATCHED));
    }

    @Test
    @DisplayName("현재 registry 형태가 아닌 wildcard pattern은 매칭하지 않는다")
    void resolveRejectsUnsupportedWildcardPattern() {
        CharacterSettingSchema schema = schema(
                "statuses.condition",
                "status*",
                CharacterFactType.STATUS,
                SettingValueType.JSON
        );

        assertThatThrownBy(() -> resolver.resolve(
                "status.악령_깃들임",
                SettingValueType.JSON,
                List.of(schema)
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_SCHEMA_NOT_MATCHED));
    }

    @Test
    @DisplayName("exact 매칭은 alias와 pattern보다 우선한다")
    void resolvePrioritizesExactOverAliasAndPattern() {
        CharacterSettingSchema exact = schema(
                "stats.strength",
                null,
                CharacterFactType.STAT,
                SettingValueType.NUMBER
        );
        CharacterSettingSchema alias = schema(
                "profile.strength",
                null,
                CharacterFactType.STATUS,
                SettingValueType.NUMBER,
                "stats.strength"
        );
        CharacterSettingSchema pattern = schema(
                "stats.dynamic",
                "stats.*",
                CharacterFactType.STATUS,
                SettingValueType.NUMBER
        );

        SettingCandidateSchemaMatch match = resolver.resolve(
                "stats.strength",
                SettingValueType.NUMBER,
                List.of(pattern, alias, exact)
        );

        assertThat(match.matchedSchema()).isSameAs(exact);
        assertThat(match.factKey()).isEqualTo("stats.strength");
    }

    @Test
    @DisplayName("alias 매칭은 pattern보다 우선한다")
    void resolvePrioritizesAliasOverPattern() {
        CharacterSettingSchema alias = schema(
                "stats.strength",
                null,
                CharacterFactType.STAT,
                SettingValueType.NUMBER,
                "힘"
        );
        CharacterSettingSchema pattern = schema(
                "stats.dynamic",
                "stats.*",
                CharacterFactType.STATUS,
                SettingValueType.NUMBER
        );

        SettingCandidateSchemaMatch match = resolver.resolve(
                "stats.힘",
                SettingValueType.NUMBER,
                List.of(pattern, alias)
        );

        assertThat(match.matchedSchema()).isSameAs(alias);
        assertThat(match.factKey()).isEqualTo("stats.strength");
    }

    @Test
    @DisplayName("대소문자가 다른 attributeName은 fuzzy 매칭하지 않는다")
    void resolveDoesNotIgnoreCase() {
        CharacterSettingSchema schema = schema(
                "stats.strength",
                null,
                CharacterFactType.STAT,
                SettingValueType.NUMBER,
                "힘"
        );

        assertThatThrownBy(() -> resolver.resolve(
                "Stats.힘",
                SettingValueType.NUMBER,
                List.of(schema)
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_SCHEMA_NOT_MATCHED));
    }

    @Test
    @DisplayName("매칭된 schema와 후보의 valueType이 다르면 거절한다")
    void resolveRejectsMismatchedValueType() {
        CharacterSettingSchema schema = schema(
                "age",
                null,
                CharacterFactType.AGE,
                SettingValueType.NUMBER,
                "나이"
        );

        assertThatThrownBy(() -> resolver.resolve(
                "age",
                SettingValueType.STRING,
                List.of(schema)
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_VALUE_TYPE_MISMATCH));
    }

    @Test
    @DisplayName("같은 단계에서 schema가 여러 개 매칭되면 임의 선택하지 않는다")
    void resolveRejectsAmbiguousMatchesWithinSameStage() {
        CharacterSettingSchema first = schema(
                "stats.strength",
                null,
                CharacterFactType.STAT,
                SettingValueType.NUMBER
        );
        CharacterSettingSchema second = schema(
                "stats.strength",
                null,
                CharacterFactType.STATUS,
                SettingValueType.NUMBER
        );

        assertThatThrownBy(() -> resolver.resolve(
                "stats.strength",
                SettingValueType.NUMBER,
                List.of(first, second)
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_SCHEMA_MATCH_AMBIGUOUS));
    }

    private CharacterSettingSchema schema(
            String schemaKey,
            String attributePattern,
            CharacterFactType factType,
            SettingValueType valueType,
            String... aliases
    ) {
        ArrayNode aliasesJson = objectMapper.createArrayNode();
        for (String alias : aliases) {
            aliasesJson.add(alias);
        }
        return CharacterSettingSchema.create(
                null,
                schemaKey,
                attributePattern,
                schemaKey,
                factType,
                valueType,
                CharacterSettingValueSemantics.BASE_VALUE,
                CharacterSettingMergePolicy.REPLACE,
                aliasesJson,
                CharacterSettingSchemaSource.SYSTEM_SEED,
                true
        );
    }
}
