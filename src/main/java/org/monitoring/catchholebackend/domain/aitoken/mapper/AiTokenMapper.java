package org.monitoring.catchholebackend.domain.aitoken.mapper;

import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenReservationResponse;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenUsageResponse;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenAccount;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenUsage;
import org.springframework.stereotype.Component;

@Component
public class AiTokenMapper {

    public AiTokenUsageResponse toResponse(AiTokenAccount account, String contactEmail) {
        long remaining = account.remainingTokens();
        double percent = account.getGrantedTokens() == 0
                ? 0
                : Math.round((remaining * 10000.0) / account.getGrantedTokens()) / 100.0;
        return new AiTokenUsageResponse(
                account.getGrantedTokens(),
                account.getUsedTokens(),
                account.getReservedTokens(),
                remaining,
                percent,
                remaining == 0,
                contactEmail
        );
    }

    public AiTokenReservationResponse toResponse(AiTokenUsage usage) {
        return new AiTokenReservationResponse(
                usage.getRequestId(),
                usage.getReservedTokens(),
                usage.getStatus()
        );
    }
}
