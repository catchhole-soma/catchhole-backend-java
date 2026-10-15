package org.monitoring.catchholebackend.domain.worldsetting.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorldSettingExtractionAssertionTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test @DisplayName("지정된 원본 자식만 확인하고 무관한 값·근거 객체의 단어를 상태로 해석하지 않는다")
    void assertedLeavesAndNestedAggregatesPreserveAllOriginalAssertions() throws Exception {
        assertThat(WorldSettingExtractionAssertion.isAsserted(json.readTree("""
                {"epistemic_status":"ASSERTED","unrelated":{"epistemic_status":"UNRESOLVED_CLAIM"},
                 "sourceCandidates":[{"epistemic_status":"ASSERTED"},
                    {"epistemic_status":"ASSERTED","sourceCandidates":[{"epistemic_status":"ASSERTED"}]}]}
                """))).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {"null", "[]", "{}", "{\"epistemic_status\":null}",
            "{\"epistemic_status\":true}", "{\"epistemic_status\":\"UNKNOWN\"}", "{\"epistemic_status\":\"UNRESOLVED_CLAIM\"}",
            "{\"unrelated\":{\"epistemic_status\":\"ASSERTED\"}}", "{\"epistemic_status\":\"ASSERTED\",\"sourceCandidates\":[]}",
            "{\"epistemic_status\":\"ASSERTED\",\"sourceCandidates\":null}", "{\"epistemic_status\":\"ASSERTED\",\"sourceCandidates\":[\"ASSERTED\"]}",
            "{\"epistemic_status\":\"ASSERTED\",\"sourceCandidates\":[{\"epistemic_status\":\"ASSERTED\"},{}]}",
            "{\"epistemic_status\":\"ASSERTED\",\"sourceCandidates\":[{\"epistemic_status\":\"ASSERTED\",\"sourceCandidates\":[{\"epistemic_status\":\"UNRESOLVED_CLAIM\"}]}]}"})
    @DisplayName("누락·불명·혼합·잘못된 wrapper는 명확한 주장으로 승격하지 않는다")
    void incompleteOrUnresolvedTreeRequiresReview(String raw) throws Exception {
        assertThat(WorldSettingExtractionAssertion.isAsserted(json.readTree(raw))).isFalse();
    }
}
