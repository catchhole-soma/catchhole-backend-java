package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisBatchStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;

@Schema(description = "업로드 배치 안의 분석 목적별 최신 작업 집계")
public record AnalysisBatchJobGroupResponse(
        @Schema(description = "분석 목적")
        AnalysisJobType jobType,

        @Schema(description = "분석 목적별 집계 상태")
        AnalysisBatchStatus status,

        @Schema(description = "현재 유효한 전체 작업 수", example = "4")
        int totalJobCount,

        @Schema(description = "분석 대기 작업 수", example = "0")
        int pendingJobCount,

        @Schema(description = "분석 진행 작업 수", example = "1")
        int runningJobCount,

        @Schema(description = "분석 성공 작업 수", example = "2")
        int succeededJobCount,

        @Schema(description = "분석 실패 작업 수", example = "1")
        int failedJobCount,

        @Schema(description = "작품 영구 삭제로 취소된 작업 수", example = "0")
        int canceledJobCount,

        @Schema(description = "진행·결과 화면에서 조회할 현재 유효 작업 ID")
        List<UUID> currentAnalysisJobIds,

        @Schema(description = "목적별 작업의 마지막 상태 변경 시각")
        LocalDateTime lastActivityAt
) {
}
