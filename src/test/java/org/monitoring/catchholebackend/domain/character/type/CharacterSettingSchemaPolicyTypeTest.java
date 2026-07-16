package org.monitoring.catchholebackend.domain.character.type;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("캐릭터 설정 Schema Registry 정책 enum 테스트")
class CharacterSettingSchemaPolicyTypeTest {

    @Test
    @DisplayName("병합 정책의 한글 표시명을 제공한다")
    void mergePolicyProvidesKoreanDisplayNames() {
        assertThat(CharacterSettingMergePolicy.values())
                .extracting(CharacterSettingMergePolicy::getToKorean)
                .containsExactly(
                        "값 교체",
                        "이름 기준 추가·갱신",
                        "슬롯 기준 추가·갱신",
                        "목록에 추가",
                        "파생값 계산"
                );
    }

    @Test
    @DisplayName("값 의미의 한글 표시명을 제공한다")
    void valueSemanticsProvidesKoreanDisplayNames() {
        assertThat(CharacterSettingValueSemantics.values())
                .extracting(CharacterSettingValueSemantics::getToKorean)
                .containsExactly("기본값", "보정값", "파생값");
    }

    @Test
    @DisplayName("Schema source의 한글 표시명을 제공한다")
    void schemaSourceProvidesKoreanDisplayNames() {
        assertThat(CharacterSettingSchemaSource.values())
                .extracting(CharacterSettingSchemaSource::getToKorean)
                .containsExactly("시스템 기본 시드", "개발 검증 시드");
    }
}
