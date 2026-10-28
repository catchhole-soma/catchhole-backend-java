// Exact pre-GH304 DTO from 4b68365aff7ca58265f43bab0c7f08a276765371; only the test package differs.
package org.monitoring.catchholebackend.support.legacy.analysis;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;

@Schema(description = "AI Worker 분석 작업 실패 요청")
public record WorkerAnalysisJobFailRequest(
        @Schema(description = "기계 판독용 실패 코드", example = "AI_TOKEN_QUOTA_EXHAUSTED", nullable = true)
        AnalysisFailureCode failureCode,

        @Schema(description = "실패 사유", example = "LLM 응답 스키마 오류")
        @NotBlank(message = "실패 사유는 필수입니다.")
        String errorMessage
) {
}
