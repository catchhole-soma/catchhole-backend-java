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
        long failedComparisonCount,
        long recomparisonRequiredCount,
        long conflictCandidateCount,
        PageResponse<WorldSettingCandidateGroupResponse> groups
) {
}
