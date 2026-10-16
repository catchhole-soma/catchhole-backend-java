package org.monitoring.catchholebackend.domain.aitoken.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenExtensionContext;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenExtensionStatus;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenExtensionSource;

@Schema(description = "운영자용 추가 AI 사용량 요청. 계정 잔액은 모델별 환산 단위이며 소수 2자리까지 제공")
public record AiTokenExtensionAdminResponse(
        UUID id,
        Long memberId,
        String memberEmail,
        String memberDisplayName,
        String feedback,
        AiTokenExtensionSource source,
        AiTokenExtensionContext context,
        AiTokenExtensionStatus status,
        LocalDateTime requestedAt,
        LocalDateTime reviewedAt,
        Long reviewedByMemberId,
        Long grantedAmount,
        String rejectionReason,
        BigDecimal grantedTokens,
        BigDecimal usedTokens,
        BigDecimal reservedTokens,
        BigDecimal remainingTokens
) {
}
