package org.monitoring.catchholebackend.domain.worldsetting.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Worker 세계관 설정 비교 실패 요청")
public record WorkerWorldSettingComparisonFailRequest(
        @NotBlank(message = "세계관 설정 비교 실패 사유는 필수입니다.")
        @Size(max = 1000, message = "세계관 설정 비교 실패 사유는 1000자 이하여야 합니다.")
        String errorMessage
) {
}
