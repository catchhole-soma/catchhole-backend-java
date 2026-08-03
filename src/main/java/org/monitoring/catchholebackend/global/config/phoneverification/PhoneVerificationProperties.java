package org.monitoring.catchholebackend.global.config.phoneverification;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.phone-verification")
public record PhoneVerificationProperties(
        String hashSecret,
        Duration codeExpiration,
        Duration resendInterval,
        Duration signupTokenExpiration,
        int maxAttempts
) {
}
