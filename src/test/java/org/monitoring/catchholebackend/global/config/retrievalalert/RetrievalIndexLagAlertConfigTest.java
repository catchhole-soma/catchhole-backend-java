package org.monitoring.catchholebackend.global.config.retrievalalert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.retrieval.mapper.RetrievalIndexLagAlertMapper;
import org.monitoring.catchholebackend.domain.retrieval.processor.RetrievalIndexLagAlertScheduler;
import org.monitoring.catchholebackend.domain.retrieval.repository.RetrievalIndexLagAlertStateStore;
import org.monitoring.catchholebackend.domain.retrieval.repository.RetrievalIndexLagRepository;
import org.monitoring.catchholebackend.global.config.chat.ChatConfig;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("검색 색인 지연 알림을 켜는 세 조건")
class RetrievalIndexLagAlertConfigTest {
    static final String WEBHOOK = "https://discord.com/api/webhooks/123456/test-only-token";

    final RetrievalIndexLagRepository repository = mock(RetrievalIndexLagRepository.class);
    final RetrievalIndexLagAlertStateStore store = mock(RetrievalIndexLagAlertStateStore.class);
    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ChatConfig.class, RetrievalIndexLagAlertConfig.class)
            .withBean(RetrievalIndexLagRepository.class, () -> repository)
            .withBean(RetrievalIndexLagAlertStateStore.class, () -> store)
            .withBean(RetrievalIndexLagAlertMapper.class, RetrievalIndexLagAlertMapper::new)
            .withPropertyValues("retrieval.index-lag-alert.scheduling-enabled=true", "chat.operations-enabled=true",
                    "chat.capability-secret=test-only-capability-secret-at-least-32-chars");

    @Test
    @DisplayName("자체 스위치·운영 백필 게이트·웹훅이 모두 있으면 발송 가능한 스케줄러를 만든다")
    void activeWhenAllSwitchesAreOn() {
        runner.withPropertyValues("analysis.notifications.discord-webhook-url=" + WEBHOOK).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RetrievalIndexLagAlertScheduler.class);
            assertThat(ReflectionTestUtils.getField(context.getBean(RetrievalIndexLagAlertScheduler.class), "client"))
                    .isNotNull();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"retrieval.index-lag-alert.scheduling-enabled=false", "chat.operations-enabled=false"})
    @DisplayName("자체 스위치나 운영 백필 게이트가 꺼지면 스케줄러를 만들지 않는다")
    void absentWhenASwitchIsOff(String off) {
        runner.withPropertyValues("analysis.notifications.discord-webhook-url=" + WEBHOOK, off)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(RetrievalIndexLagAlertScheduler.class));
    }

    @Test
    @DisplayName("운영 백필 게이트를 설정하지 않으면 자체 스위치가 켜져 있어도 스케줄러를 만들지 않는다")
    void absentByDefault() {
        new ApplicationContextRunner().withUserConfiguration(RetrievalIndexLagAlertConfig.class)
                .withPropertyValues("retrieval.index-lag-alert.scheduling-enabled=true",
                        "analysis.notifications.discord-webhook-url=" + WEBHOOK)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(RetrievalIndexLagAlertScheduler.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "https://example.com/api/webhooks/1/token", "not a url"})
    @DisplayName("웹훅이 없거나 형식이 틀리면 기동은 막지 않고 조회도 발송도 하지 않는다")
    void inertWithoutAValidWebhook(String url) {
        runner.withPropertyValues("analysis.notifications.discord-webhook-url=" + url).run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(RetrievalIndexLagAlertScheduler.class).checkRetrievalIndexLag();
            verifyNoInteractions(repository, store);
        });
    }
}
