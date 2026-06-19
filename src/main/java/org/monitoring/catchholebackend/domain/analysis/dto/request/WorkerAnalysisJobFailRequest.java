package org.monitoring.catchholebackend.domain.analysis.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "AI Worker 분석 작업 실패 요청")
public record WorkerAnalysisJobFailRequest(
        @Schema(description = "실패 사유", example = "LLM 응답 스키마 오류")
        @NotBlank(message = "실패 사유는 필수입니다.")
        String errorMessage
) {
}
