package org.monitoring.catchholebackend.global.config.chat;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChatProperties.class)
public class ChatConfig {
    @org.springframework.context.annotation.Bean
    public org.monitoring.catchholebackend.domain.chat.controller.ChatBodyLimitFilter
            chatBodyLimitFilter(ChatProperties properties) {
        return new org.monitoring.catchholebackend.domain.chat.controller.ChatBodyLimitFilter(
                properties);
    }
}
