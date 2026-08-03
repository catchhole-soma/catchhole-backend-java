package org.monitoring.catchholebackend.global.config.phoneverification;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sms")
public record SmsProperties(
        String provider,
        Solapi solapi
) {

    public boolean isFake() {
        return "fake".equalsIgnoreCase(provider);
    }

    public record Solapi(
            String apiKey,
            String apiSecret,
            String senderNumber
    ) {
    }
}
