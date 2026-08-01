package org.monitoring.catchholebackend.domain.aitoken.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenPurpose;

public record AiTokenReserveRequest(
        @NotNull UUID requestId,
        @NotNull UUID analysisJobId,
        @NotNull AiTokenPurpose purpose,
        @Min(1) int attempt,
        @NotBlank @Size(max = 100) String modelName,
        @Min(1) long reservedTokens
) {
}
