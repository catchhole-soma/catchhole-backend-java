package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonReviewReason;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingConsolidationStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation;

@Schema(description = "세계관 설정 후보 응답")
public record WorldSettingCandidateResponse(
        UUID id,
        UUID workId,
        UUID sourceEpisodeId,
        Integer sourceEpisodeNo,
        UUID analysisJobId,
        WorldSettingCategory category,
        String subjectName,
        @Schema(nullable = true) String scopeName,
        String settingName,
        String extractedValue,
        List<WorldSettingEvidenceSpanResponse> evidenceSpans,
        @Schema(nullable = true) BigDecimal extractionConfidence,
        @Schema(nullable = true) UUID targetWorldSettingId,
        @Schema(description = "2차 비교가 연결한 기존 확정 대상의 정식 대상명", nullable = true)
        String targetSubjectName,
        @Schema(description = "2차 비교가 참조한 기존 속성의 범위", nullable = true)
        String matchedScopeName,
        @Schema(description = "2차 비교가 참조한 기존 속성명", nullable = true)
        String matchedPropertyName,
        WorldSettingConsolidationStatus consolidationStatus,
        @Schema(nullable = true) WorldSettingSuggestedOperation suggestedOperation,
        @Schema(description = "사용자 판단이 필요한 구조화된 비교 사유", nullable = true)
        WorldSettingComparisonReviewReason comparisonReviewReason,
        @Schema(nullable = true) String proposedScopeName,
        @Schema(nullable = true) String proposedSettingName,
        @Schema(nullable = true) String beforeValue,
        @Schema(nullable = true) String proposedValue,
        @Schema(nullable = true) String comparisonReason,
        @Schema(nullable = true) Long baseWorldSettingVersion,
        @Schema(nullable = true) LocalDateTime comparedAt,
        WorldSettingComparisonStatus comparisonStatus,
        @Schema(nullable = true) String comparisonErrorMessage,
        @Schema(description = "기계 판독용 비교 실패 코드", nullable = true)
        AnalysisFailureCode comparisonFailureCode,
        WorldSettingReviewStatus reviewStatus,
        boolean userModified,
        @Schema(nullable = true) WorldSettingOperation finalOperation,
        @Schema(nullable = true) WorldSettingCategory finalCategory,
        @Schema(nullable = true) String finalSubjectName,
        @Schema(nullable = true) String finalScopeName,
        @Schema(nullable = true) String finalSettingName,
        @Schema(nullable = true) String finalValue,
        @Schema(nullable = true) String reviewNote,
        @Schema(nullable = true) Long reviewedById,
        @Schema(nullable = true) String reviewedByDisplayName,
        @Schema(nullable = true) LocalDateTime reviewedAt,
        @Schema(nullable = true) Long appliedWorldSettingVersion,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
