package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;

@Schema(description = "분석 작업 응답")
public record AnalysisJobResponse(
        @Schema(description = "분석 작업 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d333")
        UUID id,

        @Schema(description = "작품 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d444")
        UUID workId,

        @Schema(description = "작품 제목", example = "내 작품")
        String workTitle,

        @Schema(description = "업로드 배치 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d111")
        UUID batchId,

        @Schema(description = "분석 대상 업로드 배치 요약")
        AnalysisJobTargetResponse target,

        @Schema(description = "회차 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d222")
        UUID episodeId,

        @Schema(description = "분석 작업 유형", example = "EPISODE_VALIDATION")
        AnalysisJobType jobType,

        @Schema(description = "분석 작업 상태", example = "PENDING")
        AnalysisJobStatus status,

        @Schema(description = "현재 처리 단계", example = "원문 청킹")
        String currentStep,

        @Schema(description = "사용 모델명", example = "gpt-4.1-mini")
        String modelName,

        @Schema(description = "입력 토큰 수", example = "1200")
        Integer inputTokenCount,

        @Schema(description = "출력 토큰 수", example = "300")
        Integer outputTokenCount,

        @Schema(description = "분석 결과 요약 JSON")
        String summaryJson,

        @Schema(description = "마지막 실패 사유")
        String errorMessage,

        @Schema(description = "분석 시작 시각", example = "2026-06-14T10:30:00")
        LocalDateTime startedAt,

        @Schema(description = "분석 완료 시각", example = "2026-06-14T10:31:00")
        LocalDateTime completedAt,

        @Schema(description = "생성 시각", example = "2026-06-14T10:29:00")
        LocalDateTime createdAt,

        @Schema(description = "수정 시각", example = "2026-06-14T10:29:00")
        LocalDateTime updatedAt
) {
}
