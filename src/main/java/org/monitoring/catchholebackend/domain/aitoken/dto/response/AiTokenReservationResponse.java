package org.monitoring.catchholebackend.domain.aitoken.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageStatus;

@Schema(description = "AI 요청 토큰 예약 결과")
public record AiTokenReservationResponse(
        @Schema(description = "예약된 AI 요청 식별자")
        UUID requestId,
        @Schema(description = "환산 전 raw 예약 토큰 수 (사용자 차감량과 구분)", example = "12000")
        long reservedTokens,
        @Schema(description = "토큰 예약 상태", example = "RESERVED")
        AiTokenUsageStatus status
) {
}
