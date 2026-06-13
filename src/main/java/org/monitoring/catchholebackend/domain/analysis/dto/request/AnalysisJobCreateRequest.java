package org.monitoring.catchholebackend.domain.analysis.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;

@Schema(description = "분석 작업 생성 요청")
public record AnalysisJobCreateRequest(
        @NotNull(message = "분석 작업 유형은 필수입니다.")
        @Schema(description = "분석 작업 유형", example = "EPISODE_VALIDATION")
        AnalysisJobType jobType,

        @NotNull(message = "분석 대상 업로드 배치 ID는 필수입니다.")
        @Schema(description = "분석 대상 업로드 배치 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d111")
        UUID batchId
) {
}
