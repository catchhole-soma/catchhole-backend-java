package org.monitoring.catchholebackend.global.config.emaildelivery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "email")
public record EmailDeliveryProperties(
        @DefaultValue("fake") String provider,
        Smtp smtp
) {

    public record Smtp(
            String host,
            @DefaultValue("587") int port,
            String username,
            String password,
            String from
    ) {

        @Override
        public String toString() {
            return "Smtp[설정값 비공개]";
        }
    }
}
