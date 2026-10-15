package org.monitoring.catchholebackend.domain.analysis.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;

class SettingBookAutomaticEvidenceValidatorTest {
    private final SettingBookAutomaticEvidenceValidator validator = new SettingBookAutomaticEvidenceValidator(mock(ObjectStorageService.class));
    private final JsonNodeFactory json = JsonNodeFactory.instance;

    @Test void fullSourceAndRepeatedExactQuotesAcceptCodePointBoundariesAndBothNullOffsets() {
        String source = "📚\r\n하린 하린";
        assertThat(validator.matches(source, json.arrayNode().add(json.objectNode().put("quote", source)
                .put("startOffset", 0).put("endOffset", source.codePointCount(0, source.length()))))).isTrue();
        assertThat(validator.matches(source, json.arrayNode().add(json.objectNode().put("quote", "하린")
                .putNull("startOffset").putNull("endOffset")))).isTrue();
        assertThat(validator.matches(source, json.arrayNode().add(json.objectNode().put("quote", "하린")
                .put("start_offset", 3).put("end_offset", 5)))).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {"blank", "noEvidence", "noncontiguous", "oneNull", "negative", "reverse", "pastEnd", "utf16End", "fraction", "overflow", "conflictingAlias"})
    void invalidCitationsCannotBecomeCanonicalEvidence(String invalid) {
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
        assertThat(validator.matches(source, evidence)).isFalse();
    }
}
