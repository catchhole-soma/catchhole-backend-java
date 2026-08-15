package org.monitoring.catchholebackend.domain.character.processor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateValueValidationStatus;
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
                    valueType == SettingValueType.BOOLEAN ? "true" : "17",
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
                null,
                CharacterFactType.PROFILE,
                SettingValueType.UNKNOWN
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("NUMBER 표시값과 구조화 값은 숫자 표현이 달라도 같은 값이면 유효하다")
    void numberCandidateAcceptsNumericallyEquivalentValues() {
        SettingCandidate candidate = candidate(
                "17.00",
                JsonNodeFactory.instance.objectNode().put("value", 17)
        );

        SettingCandidateValueValidation result = validator.evaluateCandidate(
                candidate,
                CharacterFactType.STAT,
                SettingValueType.NUMBER
        );

        assertThat(result.status()).isEqualTo(SettingCandidateValueValidationStatus.VALID);
        assertThat(result.errorCode()).isNull();
    }

    @Test
    @DisplayName("NUMBER 표시값이 숫자로 해석되지 않으면 형식 오류다")
    void numberCandidateRejectsDescriptiveDisplayValue() {
        SettingCandidate candidate = candidate(
                "열일곱 살",
                JsonNodeFactory.instance.objectNode().put("value", 17)
        );

        SettingCandidateValueValidation result = validator.evaluateCandidate(
                candidate,
                CharacterFactType.STAT,
                SettingValueType.NUMBER
        );

        assertThat(result.status()).isEqualTo(SettingCandidateValueValidationStatus.INVALID);
        assertThat(result.errorCode()).isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_VALUE_FORMAT_INVALID);
    }

    @Test
    @DisplayName("NUMBER 표시값과 구조화 값이 다르면 불일치 오류다")
    void numberCandidateRejectsMismatchedValueJson() {
        SettingCandidate candidate = candidate(
                "17",
                JsonNodeFactory.instance.objectNode().put("value", 18)
        );

        SettingCandidateValueValidation result = validator.evaluateCandidate(
                candidate,
                CharacterFactType.STAT,
                SettingValueType.NUMBER
        );

        assertThat(result.status()).isEqualTo(SettingCandidateValueValidationStatus.INVALID);
        assertThat(result.errorCode()).isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_VALUE_MISMATCH);
    }

    @Test
    @DisplayName("BOOLEAN 표시값은 소문자 true 또는 false만 허용한다")
    void booleanCandidateRequiresCanonicalLowercaseDisplayValue() {
        SettingCandidate candidate = candidate(
                "TRUE",
                JsonNodeFactory.instance.objectNode().put("value", true)
        );

        SettingCandidateValueValidation result = validator.evaluateCandidate(
                candidate,
                CharacterFactType.STATUS,
                SettingValueType.BOOLEAN
        );

        assertThat(result.status()).isEqualTo(SettingCandidateValueValidationStatus.INVALID);
        assertThat(result.errorCode()).isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_VALUE_FORMAT_INVALID);
    }

    @Test
    @DisplayName("NUMBER 구조화 값은 숫자 노드여야 한다")
    void numberCandidateRejectsTextualValueJson() {
        SettingCandidate candidate = candidate(
                "17",
                JsonNodeFactory.instance.objectNode().put("value", "17")
        );

        SettingCandidateValueValidation result = validator.evaluateCandidate(
                candidate,
                CharacterFactType.STAT,
                SettingValueType.NUMBER
        );

        assertThat(result.status()).isEqualTo(SettingCandidateValueValidationStatus.INVALID);
        assertThat(result.errorCode()).isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_VALUE_JSON_INVALID);
    }

    @Test
    @DisplayName("캐릭터 발견 후보는 값 검증 대상이 아니다")
    void discoveryCandidateIsNotApplicable() {
        SettingCandidate candidate = mock(SettingCandidate.class);
        when(candidate.isCharacterDiscovery()).thenReturn(true);

        SettingCandidateValueValidation result = validator.evaluateCandidate(
                candidate,
                CharacterFactType.PROFILE,
                SettingValueType.STRING
        );

        assertThat(result.status()).isEqualTo(SettingCandidateValueValidationStatus.NOT_APPLICABLE);
        assertThat(result.errorCode()).isNull();
    }

    private SettingCandidate candidate(String attributeValue, ObjectNode valueJson) {
        SettingCandidate candidate = mock(SettingCandidate.class);
        when(candidate.isCharacterDiscovery()).thenReturn(false);
        when(candidate.getAttributeValue()).thenReturn(attributeValue);
        when(candidate.getValueJson()).thenReturn(valueJson);
        return candidate;
    }
}
