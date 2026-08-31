package org.monitoring.catchholebackend.domain.worldsetting.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonValidationReason;

@Schema(description = "Worker 세계관 설정 비교 실패 요청")
public record WorkerWorldSettingComparisonFailRequest(
        @Schema(description = "기계 판독용 비교 실패 코드", nullable = true)
        AnalysisFailureCode failureCode,

        @NotBlank(message = "세계관 설정 비교 실패 사유는 필수입니다.")
        @Size(max = 1000, message = "세계관 설정 비교 실패 사유는 1000자 이하여야 합니다.")
        String errorMessage,

        @Schema(description = "Spring 원본 도메인 오류 코드", nullable = true)
        @Size(max = 100, message = "Spring 원본 오류 코드는 100자 이하여야 합니다.")
        @Pattern(
                regexp = "^[A-Z][A-Z0-9_]*$",
                message = "Spring 원본 오류 코드는 대문자와 숫자, 밑줄만 사용할 수 있습니다."
        )
        String sourceErrorCode,

        @Schema(description = "Spring 세계관 비교 계약 검증 분기", nullable = true)
        WorldSettingComparisonValidationReason sourceReasonCode
) {
}
