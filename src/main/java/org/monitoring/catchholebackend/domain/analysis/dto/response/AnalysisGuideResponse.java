package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "계정별 최초 분석 반영 방식 안내")
public record AnalysisGuideResponse(
        @Schema(description = "분석 이력과 안내 노출 기록이 없으면 true. claim은 true인 요청만 자동 표시합니다.")
        boolean shouldShow
) {}
