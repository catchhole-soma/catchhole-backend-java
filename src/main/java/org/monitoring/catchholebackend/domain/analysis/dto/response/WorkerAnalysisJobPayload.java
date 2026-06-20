package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;

@Schema(description = "AI Worker 분석 작업 payload")
public record WorkerAnalysisJobPayload(
        @Schema(description = "분석 작업 ID")
        UUID analysisJobId,

        @Schema(description = "분석 작업 유형")
        AnalysisJobType jobType,

        @Schema(description = "작품 ID")
        UUID workId,

        @Schema(description = "작품 제목")
        String workTitle,

        @Schema(description = "업로드 배치 ID")
        UUID batchId,

        @Schema(description = "Worker가 사용할 모델명", nullable = true)
        String modelName,

        @Schema(description = "현재 처리 단계", nullable = true)
        String currentStep,

        @Schema(description = "분석 대상 회차 목록")
        List<WorkerAnalysisEpisodePayload> episodes
) {
}
