package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "세계관 설정의 1차 추출 원문 근거")
public record WorldSettingEvidenceSpanResponse(
        @Schema(description = "원문 인용문", example = "바바리안 부족은 북부 설원의 혹한 속에서 살아왔다.")
        String quote,

        @Schema(description = "원문 내 시작 위치", nullable = true, example = "120")
        Integer startOffset,

        @Schema(description = "원문 내 종료 위치", nullable = true, example = "148")
        Integer endOffset
) {
}
