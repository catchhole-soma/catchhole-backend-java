package org.monitoring.catchholebackend.domain.worldsetting.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;

@Schema(description = "검토 대기 세계관 후보 한 건의 작가 수정안")
public record WorldSettingCandidateDecisionUpdateItem(
        @NotNull UUID candidateId,
        @NotNull WorldSettingOperation operation,
        @NotNull WorldSettingCategory category,
        @NotBlank @Size(max = 100) String subjectName,
        @Size(max = 100) @Schema(description = "최종 선택적 한 단계 범위", nullable = true)
        String scopeName,
        @NotBlank @Size(max = 100) String settingName,
        @NotBlank String value,
        @Size(max = 1000) @Schema(description = "선택 검토 메모", nullable = true)
        String reviewNote
) {
}
