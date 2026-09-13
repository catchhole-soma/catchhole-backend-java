package org.monitoring.catchholebackend.domain.worldsetting.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.worldsetting.dto.WorldSettingComparisonDiagnostic;
import org.monitoring.catchholebackend.domain.worldsetting.dto.WorldSettingComparisonDiagnostic.Phase;
import org.monitoring.catchholebackend.domain.worldsetting.dto.WorldSettingComparisonDiagnostic.Stage;

@DisplayName("세계관 비교의 안전한 단계별 진단 기록")
class WorldSettingComparisonDiagnosticsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("분리 복구의 검증 단계와 규칙이 관련 후보에 그대로 보존된다")
    void preservesFailureStageAndPhaseForRelatedCandidate() {
        var diagnostic = new WorldSettingComparisonDiagnostic(4, "CANONICAL_TARGET_REQUIRED",
                List.of("C2"), List.of(), Stage.DECISION_VALIDATION, Phase.RECOVERY);
        var stored = WorldSettingComparisonDiagnostics.validate(List.of(diagnostic), Set.of("C1", "C2"),
                mapper.createObjectNode());
        var selected = WorldSettingComparisonDiagnostics.forCandidate(stored, "C2").get(0);
        assertThat(selected.path("rule").asText()).isEqualTo("CANONICAL_TARGET_REQUIRED");
        assertThat(selected.path("stage").asText()).isEqualTo("DECISION_VALIDATION");
        assertThat(selected.path("phase").asText()).isEqualTo("RECOVERY");
        assertThat(selected.path("attempt").asInt()).isEqualTo(4);
        assertThat(WorldSettingComparisonDiagnostics.forCandidate(stored, "C1").isEmpty()).isTrue();
        assertThat(selected.size()).isEqualTo(6);
    }

    @Test
    @DisplayName("과거 진단에는 단계와 복구 구분을 임의로 채우지 않는다")
    void preservesLegacyDiagnosticsWithoutInventingStage() throws Exception {
        var diagnostic = mapper.readValue("""
                {"attempt":3,"rule":"COMPARISON_VALIDATION_FAILED","candidateRefs":["C1"],"selectedProperties":[]}
                """, WorldSettingComparisonDiagnostic.class);
        var stored = WorldSettingComparisonDiagnostics.validate(List.of(diagnostic), Set.of("C1"),
                mapper.createObjectNode()).get(0);
        assertThat(stored.has("stage")).isFalse();
        assertThat(stored.has("phase")).isFalse();
        assertThat(stored.size()).isEqualTo(4);
    }

    @Test
    @DisplayName("진단 단계에는 자유 문장 대신 정해진 구분만 허용한다")
    void rejectsUnknownStageAndPhase() {
        for (String field : List.of("stage", "phase")) {
            String payload = "{\"attempt\":1,\"rule\":\"RESPONSE_SCHEMA_INVALID\",\"candidateRefs\":[],"
                    + "\"selectedProperties\":[],\"" + field + "\":\"UNKNOWN_STAGE\"}";
            assertThatThrownBy(() -> mapper.readValue(payload, WorldSettingComparisonDiagnostic.class))
                    .isInstanceOf(InvalidFormatException.class);
        }
    }
}
