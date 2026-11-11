package org.monitoring.catchholebackend.global.config.retrievalalert;

import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.domain.retrieval.mapper.RetrievalIndexLagAlertMapper;
import org.monitoring.catchholebackend.domain.retrieval.processor.RetrievalIndexLagAlertScheduler;
import org.monitoring.catchholebackend.domain.retrieval.repository.RetrievalIndexLagAlertStateStore;
import org.monitoring.catchholebackend.domain.retrieval.repository.RetrievalIndexLagRepository;
import org.monitoring.catchholebackend.global.config.analysisnotification.AnalysisNotificationProperties;
import org.monitoring.catchholebackend.global.config.chat.ChatProperties;
import org.monitoring.catchholebackend.global.notification.DiscordWebhookClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 검색 색인 백필은 운영 백필 게이트(chat.operations-enabled)가 켜져 있을 때만 돈다. 꺼진 환경에서 지연을 세면
 * 영원히 알리게 되므로 두 스위치가 모두 켜졌을 때만 만든다. 웹훅은 분석 실패 알림과 같은 URL을 쓰며,
 * 비어 있거나 형식이 틀리면 기동을 막지 않고 알림만 끈다.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = {"retrieval.index-lag-alert.scheduling-enabled", "chat.operations-enabled"},
        havingValue = "true")
@EnableConfigurationProperties({RetrievalIndexLagAlertProperties.class, AnalysisNotificationProperties.class,
        ChatProperties.class})
public class RetrievalIndexLagAlertConfig {
    // 분석 실패 Dispatcher가 DiscordWebhookClient를 타입으로 주입받으므로 이 client는 Bean으로 등록하지 않는다.
    @Bean(destroyMethod = "close")
    RetrievalIndexLagAlertScheduler retrievalIndexLagAlertScheduler(RetrievalIndexLagRepository repository,
            RetrievalIndexLagAlertStateStore stateStore, RetrievalIndexLagAlertMapper mapper,
            RetrievalIndexLagAlertProperties properties, AnalysisNotificationProperties notifications,
            ChatProperties chat) {
        return new RetrievalIndexLagAlertScheduler(repository, stateStore, mapper, webhook(notifications),
                notifications.getEnvironment(), chat.retrievalEmbeddingVersion(), properties, Clock.systemDefaultZone());
    }

    private static DiscordWebhookClient webhook(AnalysisNotificationProperties notifications) {
        String url = notifications.getDiscordWebhookUrl();
        if (url == null || url.isBlank()) {
            log.info("Discord 웹훅이 설정되지 않아 검색 색인 지연 알림을 보내지 않습니다.");
            return null;
        }
        try {
            return new DiscordWebhookClient(notifications.webhookUri());
        } catch (IllegalArgumentException ex) {
            // 예외 문구에도 URL은 없다. 잘못된 웹훅 하나로 API 기동을 막지 않는다.
            log.warn("Discord 웹훅 설정이 올바르지 않아 검색 색인 지연 알림을 보내지 않습니다.");
            return null;
        }
    }
}
