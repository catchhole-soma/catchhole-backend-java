package org.monitoring.catchholebackend.global.config.ai;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ai-token")
public record AiTokenProperties(
        @Min(0) long defaultGrant,
        @NotBlank String contactEmail,
        @Min(1) long minimumAnalysisReservation,
        @Min(1) long minimumComparisonReservation
) {
}
