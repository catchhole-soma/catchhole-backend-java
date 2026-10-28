package org.monitoring.catchholebackend.domain.analysis.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.monitoring.catchholebackend.domain.analysis.type.EvidenceValidationResult;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;

@DisplayName("고정 원문 인용의 검증 결과")
class SettingBookAutomaticEvidenceValidatorTest {
    private final SettingBookAutomaticEvidenceValidator validator = new SettingBookAutomaticEvidenceValidator(mock(ObjectStorageService.class));
    private final JsonNodeFactory json = JsonNodeFactory.instance;

    @Test @DisplayName("정확한 Unicode 위치와 위치 없는 원문 인용은 VALID다")
    void fullSourceAndRepeatedExactQuotesAcceptCodePointBoundariesAndBothNullOffsets() {
        String source = "📚\r\n하린 하린";
        assertThat(validator.matches(source, json.arrayNode().add(json.objectNode().put("quote", source)
                .put("startOffset", 0).put("endOffset", source.codePointCount(0, source.length()))))).isTrue();
        assertThat(validator.matches(source, json.arrayNode().add(json.objectNode().put("quote", "하린")
                .putNull("startOffset").putNull("endOffset")))).isTrue();
        assertThat(validator.matches(source, json.arrayNode().add(json.objectNode().put("quote", "하린")
                .put("start_offset", 3).put("end_offset", 5)))).isTrue();
        assertThat(validator.validate(source, json.arrayNode().add(json.objectNode().put("quote", "하린"))))
                .isEqualTo(EvidenceValidationResult.VALID);
    }

    @ParameterizedTest @CsvSource({"blank,MALFORMED_SPANS", "noEvidence,MALFORMED_SPANS", "noncontiguous,QUOTE_NOT_FOUND",
            "oneNull,INVALID_OFFSETS", "negative,INVALID_OFFSETS", "reverse,INVALID_OFFSETS", "pastEnd,INVALID_OFFSETS",
            "utf16End,INVALID_OFFSETS", "fraction,INVALID_OFFSETS", "overflow,INVALID_OFFSETS", "conflictingAlias,INVALID_OFFSETS"})
    @DisplayName("구조·위치 오류와 위치 없는 인용 불일치를 구분하고 boolean 호환 검증은 거절한다")
    void invalidCitationsCannotBecomeCanonicalEvidence(String invalid, EvidenceValidationResult expected) {
        String source = "📚\r\n하린";
        var span = json.objectNode().put("quote", source).put("startOffset", 0).put("endOffset", source.codePointCount(0, source.length()));
        var evidence = json.arrayNode().add(span);
        switch (invalid) {
            case "blank" -> span.put("quote", " \r\n\t").putNull("startOffset").putNull("endOffset");
            case "noEvidence" -> evidence.removeAll();
            case "noncontiguous" -> span.put("quote", "📚하린").putNull("startOffset").putNull("endOffset");
            case "oneNull" -> span.putNull("endOffset");
            case "negative" -> span.put("startOffset", -1);
            case "reverse" -> span.put("startOffset", 5).put("endOffset", 0);
            case "pastEnd" -> span.put("endOffset", 7);
            case "utf16End" -> span.put("endOffset", source.length());
            case "fraction" -> span.put("startOffset", 0.5);
            case "overflow" -> span.put("startOffset", 4294967297L);
            case "conflictingAlias" -> span.put("start_offset", 1).put("end_offset", 5);
        }
        assertThat(validator.validate(source, evidence)).isEqualTo(expected);
        assertThat(validator.matches(source, evidence)).isFalse();
    }

    @Test @DisplayName("앞선 인용 불일치보다 뒤 span의 구조·명시 위치 오류를 먼저 판정한다")
    void missingQuoteCannotHideLaterMalformedStructureOrOffsets() {
        var evidence = json.arrayNode().add(json.objectNode().put("quote", "없는 문장"));
        evidence.add(12);
        assertThat(validator.validate("원문", evidence)).isEqualTo(EvidenceValidationResult.MALFORMED_SPANS);
        evidence.remove(1);
        evidence.add(json.objectNode().put("quote", "원문").put("startOffset", -1).put("endOffset", 2));
        assertThat(validator.validate("원문", evidence)).isEqualTo(EvidenceValidationResult.INVALID_OFFSETS);
        evidence.remove(1);
        evidence.add(json.objectNode().put("quote", "없는 인용").put("startOffset", 0).put("endOffset", 2));
        assertThat(validator.validate("원문", evidence)).isEqualTo(EvidenceValidationResult.INVALID_OFFSETS);
    }
}
