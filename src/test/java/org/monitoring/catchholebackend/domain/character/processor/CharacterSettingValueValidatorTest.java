package org.monitoring.catchholebackend.domain.character.processor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.global.exception.AppException;

@DisplayName("캐릭터 설정 구조화 값 검증")
class CharacterSettingValueValidatorTest {

    private final CharacterSettingValueValidator validator = new CharacterSettingValueValidator();

    @Test
    @DisplayName("타입이 선언된 scalar 설정은 null 값을 허용하지 않는다")
    void typedScalarEnvelopeRejectsNullValue() {
        ObjectNode valueJson = JsonNodeFactory.instance.objectNode().putNull("value");

        for (SettingValueType valueType : new SettingValueType[]{
                SettingValueType.STRING,
                SettingValueType.NUMBER,
                SettingValueType.BOOLEAN
        }) {
            assertThatThrownBy(() -> validator.validateProposal(
                    valueJson,
                    CharacterFactType.PROFILE,
                    valueType
            )).isInstanceOfSatisfying(AppException.class, exception ->
                    org.assertj.core.api.Assertions.assertThat(exception.getResultCode())
                            .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_VALUE_JSON_INVALID));
        }
    }

    @Test
    @DisplayName("타입을 알 수 없는 설정은 null scalar envelope를 허용한다")
    void unknownScalarEnvelopeAllowsNullValue() {
        ObjectNode valueJson = JsonNodeFactory.instance.objectNode().putNull("value");

        assertThatCode(() -> validator.validateProposal(
                valueJson,
                CharacterFactType.PROFILE,
                SettingValueType.UNKNOWN
        )).doesNotThrowAnyException();
    }
}
