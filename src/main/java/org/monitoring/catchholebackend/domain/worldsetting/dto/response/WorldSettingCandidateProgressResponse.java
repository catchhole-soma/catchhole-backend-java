package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "회차 업로드 배치의 세계관 비교 진행 집계")
public record WorldSettingCandidateProgressResponse(
        @Schema(description = "검토 대기 후보 중 비교 대기 수", minimum = "0") long pendingComparisonCount,
        @Schema(description = "검토 대기 후보 중 비교 처리 수", minimum = "0") long processingComparisonCount,
        @Schema(description = "검토 대기 후보 중 사용량 부족으로 비교가 중단된 수", minimum = "0") long tokenInterruptedComparisonCount
) {}
