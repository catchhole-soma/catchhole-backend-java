package org.monitoring.catchholebackend.domain.analysis.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "AI Worker 분석 작업 진행 단계 갱신 요청")
public record WorkerAnalysisJobProgressRequest(
        @Schema(description = "현재 처리 단계", example = "LLM 전처리")
        @NotBlank(message = "현재 처리 단계는 필수입니다.")
        @Size(max = 100, message = "현재 처리 단계는 100자 이하로 입력해주세요.")
        String currentStep
) {
}
