package org.monitoring.catchholebackend.global.config.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "internal")
public record InternalApiProperties(
        String apiKey
) {
}
