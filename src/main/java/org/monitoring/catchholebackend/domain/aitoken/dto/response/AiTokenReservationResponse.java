package org.monitoring.catchholebackend.domain.aitoken.dto.response;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageStatus;

public record AiTokenReservationResponse(
        UUID requestId,
        long reservedTokens,
        AiTokenUsageStatus status
) {
}
