package org.monitoring.catchholebackend.domain.analysis.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "AI Worker 분석 작업 claim 요청")
public record WorkerAnalysisJobClaimRequest(
        @Schema(description = "Worker가 사용할 모델명", example = "gpt-4.1-mini", nullable = true)
        @Size(max = 100, message = "모델명은 100자 이하로 입력해주세요.")
        String modelName,

        @Schema(description = "Worker가 기록할 현재 처리 단계", example = "원문 청킹", nullable = true)
        @Size(max = 100, message = "현재 처리 단계는 100자 이하로 입력해주세요.")
        String currentStep
) {
}
