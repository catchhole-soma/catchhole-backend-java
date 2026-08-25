package org.monitoring.catchholebackend.domain.aitoken.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenExtensionContext;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenExtensionStatus;

@Schema(description = "운영자용 추가 AI 사용량 요청")
public record AiTokenExtensionAdminResponse(
        UUID id,
        Long memberId,
        String memberEmail,
        String memberDisplayName,
        String feedback,
        AiTokenExtensionContext context,
        AiTokenExtensionStatus status,
        LocalDateTime requestedAt,
        LocalDateTime reviewedAt,
        Long reviewedByMemberId,
        Long grantedAmount,
        String rejectionReason,
        long grantedTokens,
        long usedTokens,
        long reservedTokens,
        long remainingTokens
) {
}
