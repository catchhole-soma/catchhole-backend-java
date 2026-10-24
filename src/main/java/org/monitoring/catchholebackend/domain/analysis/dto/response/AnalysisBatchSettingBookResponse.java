package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisBatchSettingBookStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;

@Schema(description = "본문이나 작업 상세 조회가 필요 없는 설정집 분석 카드")
public record AnalysisBatchSettingBookResponse(
        UUID settingBookId, UUID sourceAnalysisJobId, UUID batchId, UUID revisionId, String settingBookName,
        AnalysisBatchSettingBookStatus status,
        @Schema(nullable = true) AnalysisFailureCode failureCode,
        @Schema(nullable = true) String currentStep, boolean contentChangedSinceAnalysis, boolean historical,
        LocalDateTime lastActivityAt, AnalysisBatchCandidateCountsResponse characterCandidates,
        AnalysisBatchCandidateCountsResponse worldCandidates
) {}
