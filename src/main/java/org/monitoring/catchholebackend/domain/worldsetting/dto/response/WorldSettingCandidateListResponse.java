package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.monitoring.catchholebackend.global.common.response.PageResponse;

@Schema(description = "업로드 묶음별 세계관 설정 후보 검토 목록")
public record WorldSettingCandidateListResponse(
        UUID batchId,
        @Schema(nullable = true) Integer episodeStartNo,
        @Schema(nullable = true) Integer episodeEndNo,
        long episodeCount,
        long totalCandidateCount,
        long reviewedCandidateCount,
        long pendingCandidateCount,
        long pendingComparisonCount,
        long processingComparisonCount,
        @Schema(description = "PENDING 또는 RUNNING 상태인 세계관 후보 비교 Job 수", example = "2")
        long activeComparisonJobCount,
        long failedComparisonCount,
        long tokenInterruptedComparisonCount,
        boolean canResumeTokenInterruptedComparisons,
        long recomparisonRequiredCount,
        long conflictCandidateCount,
        @Schema(description = "작품 설정 또는 이력에 반영한 후보 수; 필터·페이지와 무관한 묶음 전체", requiredMode = Schema.RequiredMode.REQUIRED)
        long confirmedCandidateCount,
        @Schema(description = "제외한 후보 수; 필터·페이지와 무관한 묶음 전체", requiredMode = Schema.RequiredMode.REQUIRED)
        long dismissedCandidateCount,
        @Schema(description = "비교 대기·진행과 회차 자동 반영 대기를 제외하고 직접 확인할 미확정 후보 수; 필터·페이지와 무관한 묶음 전체", requiredMode = Schema.RequiredMode.REQUIRED)
        long directReviewCandidateCount,
        @Schema(description = "비교 대기·진행 또는 회차 자동 반영을 기다리는 미확정 후보 수; 필터·페이지와 무관한 묶음 전체", requiredMode = Schema.RequiredMode.REQUIRED)
        long processingCandidateCount,
        PageResponse<WorldSettingCandidateGroupResponse> groups
) {
}
