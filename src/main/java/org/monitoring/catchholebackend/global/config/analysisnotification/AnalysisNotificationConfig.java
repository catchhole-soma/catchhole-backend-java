package org.monitoring.catchholebackend.global.config.analysisnotification;

import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisFailureNotificationDispatcher;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisFailureNotificationRepository;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisFailureNotificationMapper;
import org.monitoring.catchholebackend.global.notification.DiscordWebhookClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "analysis.notifications.enabled", havingValue = "true")
@EnableConfigurationProperties(AnalysisNotificationProperties.class)
public class AnalysisNotificationConfig {
    @Bean
    DiscordWebhookClient analysisDiscordWebhookClient(AnalysisNotificationProperties properties) {
        return new DiscordWebhookClient(properties.webhookUri());
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    AnalysisFailureNotificationDispatcher analysisFailureNotificationDispatcher(
            AnalysisFailureNotificationRepository repository, DiscordWebhookClient client,
            AnalysisFailureNotificationMapper mapper, AnalysisNotificationProperties properties) {
        return new AnalysisFailureNotificationDispatcher(repository, client, mapper, properties.getEnvironment());
    }
}
