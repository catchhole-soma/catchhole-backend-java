package org.monitoring.catchholebackend.domain.aitoken.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenExtensionAdminResponse;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenExtensionPendingResponse;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenExtensionRequestResponse;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenReservationResponse;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenUsageResponse;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenAccount;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenExtensionRequest;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenUsage;
import org.springframework.stereotype.Component;

@Component
public class AiTokenMapper {

    public AiTokenUsageResponse toResponse(
            AiTokenAccount account,
            long defaultGrant,
            String contactEmail
    ) {
        BigDecimal remaining = account.remainingTokens();
        BigDecimal percentBasis = defaultGrant > 0 ? BigDecimal.valueOf(defaultGrant) : account.getGrantedTokens();
        double percent = percentBasis.signum() == 0
                ? 0
                : remaining.min(percentBasis).multiply(BigDecimal.valueOf(100))
                        .divide(percentBasis, 2, RoundingMode.HALF_UP).doubleValue();
        return new AiTokenUsageResponse(
                account.getGrantedTokens(),
                account.getUsedTokens(),
                account.getReservedTokens(),
                remaining,
                percent,
                remaining.signum() == 0,
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

    public AiTokenExtensionRequestResponse toResponse(AiTokenExtensionRequest request) {
        return new AiTokenExtensionRequestResponse(
                request.getId(),
                request.getFeedback(),
                request.getSource(),
                request.getContext(),
                request.getStatus(),
                request.getCreatedAt(),
                request.getReviewedAt(),
                request.getGrantedAmount(),
                request.getRejectionReason()
        );
    }

    public AiTokenExtensionPendingResponse toPendingResponse(Optional<AiTokenExtensionRequest> request) {
        return new AiTokenExtensionPendingResponse(
                request.isPresent(),
                request.map(this::toResponse).orElse(null)
        );
    }

    public AiTokenExtensionAdminResponse toAdminResponse(
            AiTokenExtensionRequest request,
            AiTokenAccount account
    ) {
        return new AiTokenExtensionAdminResponse(
                request.getId(),
                request.getMember().getId(),
                request.getMember().getEmail(),
                request.getMember().getDisplayName(),
                request.getFeedback(),
                request.getSource(),
                request.getContext(),
                request.getStatus(),
                request.getCreatedAt(),
                request.getReviewedAt(),
                request.getReviewedByMemberId(),
                request.getGrantedAmount(),
                request.getRejectionReason(),
                account.getGrantedTokens(),
                account.getUsedTokens(),
                account.getReservedTokens(),
                account.remainingTokens()
        );
    }
}
