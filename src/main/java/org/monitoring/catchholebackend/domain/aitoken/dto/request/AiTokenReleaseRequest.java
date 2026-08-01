package org.monitoring.catchholebackend.domain.aitoken.dto.request;

import jakarta.validation.constraints.NotNull;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;

public record AiTokenReleaseRequest(
        @NotNull AiTokenUsageOutcome outcome
) {
}
