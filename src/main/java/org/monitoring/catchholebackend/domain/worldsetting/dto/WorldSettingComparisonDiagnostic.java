package org.monitoring.catchholebackend.domain.worldsetting.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

@Schema(description = "비교 입력에 포함된 후보·기존 경로와 검증 규칙만 담은 안전한 시도별 진단")
public record WorldSettingComparisonDiagnostic(
        @Min(1) @Max(30) int attempt,
        @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Z][A-Z0-9_]*") String rule,
        @NotNull @Size(max = 20) List<@NotNull @Pattern(regexp = "C[1-9][0-9]*") String> candidateRefs,
        @Valid @NotNull @Size(max = 20) List<@NotNull SelectedProperty> selectedProperties,
        @Schema(description = "검증 단계. 과거 진단에는 없을 수 있습니다.", nullable = true) Stage stage,
        @Schema(description = "전체 비교 또는 분리 복구 구분. 과거 진단에는 없을 수 있습니다.", nullable = true) Phase phase
) {
    public WorldSettingComparisonDiagnostic(int attempt, String rule, List<String> candidateRefs,
            List<SelectedProperty> selectedProperties) {
        this(attempt, rule, candidateRefs, selectedProperties, null, null);
    }

    @Schema(name = "WorldSettingComparisonDiagnosticStage")
    public enum Stage {
        RESPONSE_SCHEMA, PROPERTY_SELECTION, DECISION_VALIDATION, SCOPE_PLAN, PROJECTED_SCOPE_PLAN
    }

    @Schema(name = "WorldSettingComparisonDiagnosticPhase")
    public enum Phase { BATCH, RECOVERY }

    @Schema(description = "고정 비교 입력에서 선택한 실제 기존 속성 경로. 값이나 원문은 포함하지 않습니다.")
    public record SelectedProperty(
            UUID targetWorldSettingId,
            @Size(max = 160) String provisionalSubjectKey,
            @Size(max = 100) String scopeName,
            @NotBlank @Size(max = 100) String propertyName
    ) {
        @AssertTrue(message = "진단 경로에는 실제 대상과 임시 대상 중 정확히 하나가 필요합니다.")
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isTargetReferenceValid() {
            return (targetWorldSettingId == null) != (provisionalSubjectKey == null);
        }
    }
}
