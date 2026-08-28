package org.monitoring.catchholebackend.domain.aitoken.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenExtensionContext;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenExtensionStatus;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenExtensionSource;

@Schema(description = "내 추가 AI 사용량 요청")
public record AiTokenExtensionRequestResponse(
        UUID id,
        String feedback,
        AiTokenExtensionSource source,
        AiTokenExtensionContext context,
        AiTokenExtensionStatus status,
        LocalDateTime requestedAt,
        LocalDateTime reviewedAt,
        Long grantedAmount,
        String rejectionReason
) {
}
