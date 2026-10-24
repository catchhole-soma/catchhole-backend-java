package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "설정집 카드의 원본 분석 작업별 후보 집계")
public record AnalysisBatchCandidateCountsResponse(
        long totalCandidateCount, long reviewedCandidateCount, long pendingCandidateCount,
        long directReviewCandidateCount, long processingCandidateCount
) {}
