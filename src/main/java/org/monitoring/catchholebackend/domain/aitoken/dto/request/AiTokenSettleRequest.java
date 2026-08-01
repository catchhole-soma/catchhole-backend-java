package org.monitoring.catchholebackend.domain.aitoken.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;

public record AiTokenSettleRequest(
        @Min(0) long inputTokens,
        @Min(0) long cachedInputTokens,
        @Min(0) long outputTokens,
        @NotNull AiTokenUsageOutcome outcome
) {
}
