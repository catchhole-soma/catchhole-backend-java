package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisBatchStatus;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;

@Schema(description = "분석 목록에 표시할 업로드 배치별 최신 분석 집계")
public record AnalysisBatchSummaryResponse(
        @Schema(description = "업로드 배치 ID")
        UUID batchId,

        @Schema(description = "업로드 유형")
        UploadType uploadType,

        @Schema(description = "배치 전체 집계 상태")
        AnalysisBatchStatus status,

        @Schema(description = "분석 대상 시작 회차", nullable = true, example = "5")
        Integer episodeStartNo,

        @Schema(description = "분석 대상 종료 회차", nullable = true, example = "8")
        Integer episodeEndNo,

        @Schema(description = "서로 다른 분석 대상 회차 수", example = "4")
        int episodeCount,

        @Schema(description = "배치에서 생성된 캐릭터 설정 후보 수", example = "46")
        long totalCandidateCount,

        @Schema(description = "확정 또는 무시한 캐릭터 설정 후보 수", example = "3")
        long reviewedCandidateCount,

        @Schema(description = "검토 대기 캐릭터 설정 후보 수", example = "43")
        long pendingCandidateCount,

        @Schema(description = "배치에서 생성된 세계관 설정 후보 수", example = "12")
        long worldSettingTotalCandidateCount,

        @Schema(description = "확정 또는 무시한 세계관 설정 후보 수", example = "4")
        long worldSettingReviewedCandidateCount,

        @Schema(description = "검토 대기 세계관 설정 후보 수", example = "8")
        long worldSettingPendingCandidateCount,

        @Schema(description = "토큰 부족으로 비교가 중단되어 재개 가능한 세계관 후보 수", example = "5")
        long worldSettingTokenInterruptedCandidateCount,

        @Schema(description = "토큰 부족으로 중단된 세계관 비교의 일괄 재개 가능 여부")
        boolean canResumeTokenInterruptedWorldSettingComparisons,

        @Schema(description = "분석 목적별 최신 작업 집계")
        List<AnalysisBatchJobGroupResponse> jobGroups,

        @Schema(description = "배치의 최초 분석 요청 시각")
        LocalDateTime firstRequestedAt,

        @Schema(description = "배치의 최근 분석 요청 시각")
        LocalDateTime lastRequestedAt,

        @Schema(description = "배치 작업의 마지막 상태 변경 시각")
        LocalDateTime lastActivityAt
) {
}
