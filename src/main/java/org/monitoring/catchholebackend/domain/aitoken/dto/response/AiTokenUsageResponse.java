package org.monitoring.catchholebackend.domain.aitoken.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인 사용자의 AI 토큰 사용량")
public record AiTokenUsageResponse(
        long grantedTokens,
        long usedTokens,
        long reservedTokens,
        long remainingTokens,
        double remainingPercent,
        boolean exhausted,
        String contactEmail
) {
}
