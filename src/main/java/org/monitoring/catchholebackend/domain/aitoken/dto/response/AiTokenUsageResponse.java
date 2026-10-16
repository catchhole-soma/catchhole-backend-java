package org.monitoring.catchholebackend.domain.aitoken.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "로그인 사용자의 AI 토큰 사용량")
public record AiTokenUsageResponse(
        @Schema(description = "계정에 누적 지급된 토큰 수", example = "2000000")
        BigDecimal grantedTokens,
        @Schema(description = "모델 정책으로 환산한 누적 차감량 (소수 2자리)", example = "367925")
        BigDecimal usedTokens,
        @Schema(description = "모델 정책으로 환산한 처리 중 예약량 (소수 2자리)", example = "12000")
        BigDecimal reservedTokens,
        @Schema(description = "새 요청에 사용할 수 있는 남은 사용량 (소수 2자리)", example = "1620075")
        BigDecimal remainingTokens,
        @Schema(description = "현재 1회 제공량 대비 남은 사용량 비율(최대 100%)", example = "81.0")
        double remainingPercent,
        @Schema(description = "새 AI 요청을 시작할 수 없는 소진 여부", example = "false")
        boolean exhausted,
        @Schema(description = "추가 사용량 문의 이메일", example = "aicatchhole@gmail.com")
        String contactEmail
) {
}
