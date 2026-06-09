package org.monitoring.catchholebackend.global.config.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        Jwt jwt,
        Duration refreshTokenExpiration,
        Cookie cookie
) {

    public record Jwt(
            String secret,
            Duration accessTokenExpiration
    ) {
    }

    public record Cookie(
            boolean secure,
            String sameSite
    ) {
    }
}
