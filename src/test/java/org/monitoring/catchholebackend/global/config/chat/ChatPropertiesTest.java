package org.monitoring.catchholebackend.global.config.chat;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

@DisplayName("공개 채팅과 운영 백필의 독립 설정 검증")
class ChatPropertiesTest {
    private static final String SECRET = "test-capability-secret-32-characters";
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ChatProperties.class)
    static class PropertiesConfig {}

    @Test
    @DisplayName("두 게이트 기본값은 false이고 비밀값·Python 주소 없이 기동한다")
    void defaultsRemainDisabled() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(ChatProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.operationsEnabled()).isFalse();
            assertThat(properties.capabilityConfigured()).isFalse();
        });
    }

    @Test
    @DisplayName("공개 채팅을 닫은 운영 백필은 비밀값만 있으면 Python 주소 없이 기동한다")
    void operationDoesNotRequirePublicChatOrPythonUrl() {
        runner.withPropertyValues("chat.operations-enabled=true", "chat.capability-secret=" + SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(ChatProperties.class);
                    assertThat(properties.enabled()).isFalse();
                    assertThat(properties.operationsEnabled()).isTrue();
                    assertThat(properties.capabilityConfigured()).isTrue();
                });
    }

    @Test
    @DisplayName("어느 게이트든 켜면 없거나 32자 미만인 실행 비밀값을 거부한다")
    void eitherGateRequiresCapabilitySecret() {
        for (String gate : new String[] {"chat.enabled=true", "chat.operations-enabled=true"}) {
            for (String secret : new String[] {"", "x".repeat(31)}) {
                runner.withPropertyValues(gate, "chat.python-url=http://127.0.0.1:1",
                                "chat.capability-secret=" + secret)
                        .run(context -> assertThat(context).hasFailed());
            }
        }
    }

    @Test
    @DisplayName("정확히 32자의 비밀값은 허용하고 공백만 있는 비밀값은 거부한다")
    void checksCapabilitySecretBoundary() {
        assertThat(properties(false, true, null, "x".repeat(32)).capabilityConfigured()).isTrue();
        assertThatThrownBy(() -> properties(false, true, null, " ".repeat(32)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("공개 채팅은 운영 게이트와 무관하게 HTTP Python 주소도 요구한다")
    void publicChatRequiresPythonUrl() {
        assertThatThrownBy(() -> properties(true, false, null, SECRET))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(true, false, "ftp://private", SECRET))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(properties(true, false, "http://private:8082", SECRET).enabled()).isTrue();
    }

    private ChatProperties properties(boolean enabled, boolean operations, String url, String secret) {
        return new ChatProperties(enabled, operations, url, secret,
                0, 0, 0, 0, 0, 0, 0, 0, null, null, null);
    }
}
