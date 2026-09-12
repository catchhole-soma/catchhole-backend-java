package org.monitoring.catchholebackend.global.config.emailverification;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "auth.email-verification")
public record EmailVerificationProperties(
        String hashSecret,
        @DefaultValue("5m") Duration codeExpiration,
        @DefaultValue("60s") Duration resendInterval,
        @DefaultValue("10m") Duration signupTokenExpiration,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("5") int emailHourlyLimit,
        @DefaultValue("10") int emailDailyLimit,
        @DefaultValue("10") int ipHourlyLimit,
        @DefaultValue("20") int ipDailyLimit,
        @DefaultValue("100") int globalDailyLimit,
        @DefaultValue("3000") int globalMonthlyLimit
) {
}
