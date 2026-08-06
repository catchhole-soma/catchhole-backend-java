package org.monitoring.catchholebackend.domain.worldsetting.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "세계관 설정 후보 제외 요청")
public record WorldSettingCandidateDismissRequest(
        @Size(max = 1000)
        @Schema(description = "선택 검토 메모", nullable = true)
        String reviewNote
) {
}
