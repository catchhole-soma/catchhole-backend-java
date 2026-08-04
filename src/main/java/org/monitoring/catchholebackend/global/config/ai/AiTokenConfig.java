package org.monitoring.catchholebackend.global.config.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AiTokenProperties.class)
public class AiTokenConfig {
}
