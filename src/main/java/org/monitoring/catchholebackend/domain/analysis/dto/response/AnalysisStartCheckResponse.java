package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "새 회차 분석 전 검토 조건. 직접 검토는 완료해야 하고 애매한 후보 종료에는 명시적인 동의가 필요합니다.")
public record AnalysisStartCheckResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "먼저 끝나야 할 이전 회차 분석 수") long activeEarlierAnalysisCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long manualReviewCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long ambiguousCharacterCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long ambiguousWorldCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long ambiguousRelationshipCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "작품·대상 회차·후보 상태에 결속된 확인 값") String revision
) {}
