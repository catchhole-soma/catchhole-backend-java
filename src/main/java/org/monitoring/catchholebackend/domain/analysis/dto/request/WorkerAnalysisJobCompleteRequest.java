package org.monitoring.catchholebackend.domain.analysis.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;

@Schema(description = "AI Worker 분석 작업 완료 요청")
public record WorkerAnalysisJobCompleteRequest(
        @Schema(description = "분석 결과 요약 JSON", nullable = true)
        String summaryJson,

        @Schema(description = "입력 토큰 수", example = "1200", nullable = true)
        @Min(value = 0, message = "입력 토큰 수는 0 이상이어야 합니다.")
        Integer inputTokenCount,

        @Schema(description = "출력 토큰 수", example = "300", nullable = true)
        @Min(value = 0, message = "출력 토큰 수는 0 이상이어야 합니다.")
        Integer outputTokenCount
) {
}
