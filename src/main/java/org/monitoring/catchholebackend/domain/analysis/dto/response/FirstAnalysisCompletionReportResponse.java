package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "계정별 첫 분석 완료 전환 이벤트의 일회 보고 대상 여부")
public record FirstAnalysisCompletionReportResponse(
        @Schema(description = "첫 설정 추출을 완료했고 아직 보고하지 않았으면 true. claim은 true를 받은 요청만 이벤트를 보냅니다.",
                example = "true")
        boolean shouldReport
) {}
