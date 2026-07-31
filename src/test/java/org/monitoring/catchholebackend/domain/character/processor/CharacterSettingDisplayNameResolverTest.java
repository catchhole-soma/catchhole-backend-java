package org.monitoring.catchholebackend.domain.character.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingSchemaSource;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingValueSemantics;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;

@DisplayName("캐릭터 설정 표시명 해석 단위 테스트")
class CharacterSettingDisplayNameResolverTest {

    private final CharacterSettingDisplayNameResolver resolver =
            new CharacterSettingDisplayNameResolver(new CharacterSettingEditPolicyResolver());

    @Test
    @DisplayName("exact key는 내부 suffix 대신 schema 한글 표시명을 사용한다")
    void exactKeyUsesSchemaDisplayName() {
        CharacterSettingSchema schema = schema(
                "stats.combat_power",
                null,
                "전투지수",
                CharacterFactType.STAT
        );

        String displayName = resolver.resolve(
                CharacterFactType.STAT,
                "stats.combat_power",
                null,
                List.of(schema)
        );

        assertThat(displayName).isEqualTo("전투지수");
    }

    @Test
    @DisplayName("pattern key는 고정 prefix를 제거하고 suffix를 사용자용 이름으로 정규화한다")
    void patternKeyUsesNormalizedSuffix() {
        CharacterSettingSchema schema = schema(
                "skills.skill",
                "skill.*",
                "스킬",
                CharacterFactType.SKILL
        );

        String displayName = resolver.resolve(
                CharacterFactType.SKILL,
                "skill.화염_검술",
                null,
                List.of(schema)
        );

        assertThat(displayName).isEqualTo("화염 검술");
    }

    @Test
    @DisplayName("레거시 manual key는 JSON name을 우선하고 앞뒤 공백을 제거한다")
    void manualKeyUsesJsonName() {
        var valueJson = JsonNodeFactory.instance.objectNode().put("name", "  좌우명  ");

        String displayName = resolver.resolve(
                CharacterFactType.PROFILE,
                "profile.manual_legacy",
                valueJson,
                List.of()
        );

        assertThat(displayName).isEqualTo("좌우명");
    }

    @Test
    @DisplayName("schema와 JSON name이 없으면 key suffix를 빈칸 없는 표시명으로 사용한다")
    void customKeyFallsBackToNormalizedSuffix() {
        String displayName = resolver.resolve(
                CharacterFactType.STAT,
                "stats.soul_power",
                null,
                List.of()
        );

        assertThat(displayName).isEqualTo("soul power");
    }

    private CharacterSettingSchema schema(
            String schemaKey,
            String attributePattern,
            String displayName,
            CharacterFactType factType
    ) {
        return CharacterSettingSchema.create(
                null,
                schemaKey,
                attributePattern,
                displayName,
                factType,
                SettingValueType.JSON,
                CharacterSettingValueSemantics.BASE_VALUE,
                CharacterSettingMergePolicy.REPLACE,
                JsonNodeFactory.instance.arrayNode(),
                CharacterSettingSchemaSource.SYSTEM_SEED,
                true
        );
    }
}
