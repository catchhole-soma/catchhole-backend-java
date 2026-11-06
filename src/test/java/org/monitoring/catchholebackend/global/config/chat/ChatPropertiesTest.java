package org.monitoring.catchholebackend.global.config.chat;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("공개 채팅·운영 백필 게이트와 실행 상한 설정 검증")
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

    @Test
    @DisplayName("본문·호출·도구·예약 토큰·실행 시간의 기본값은 그대로다")
    void limitDefaultsStayUnchanged() {
        assertDefaultLimits(properties(false, false, null, null));
    }

    @Test
    @DisplayName("본문 상한은 512바이트부터 1MiB까지 허용하고 경계를 1바이트라도 벗어나면 거부한다")
    void maxBodyBytesBoundary() {
        assertThat(bodyLimit(512).maxBodyBytes()).isEqualTo(512);
        assertThat(bodyLimit(131_073).maxBodyBytes()).isEqualTo(131_073);
        assertThat(bodyLimit(1_048_576).maxBodyBytes()).isEqualTo(1_048_576);
        assertThatThrownBy(() -> bodyLimit(511)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bodyLimit(1_048_577)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("탐색형 답변을 켤 때 올리는 다섯 환경변수는 application.yml을 거쳐 그대로 바인딩된다")
    void raisedLimitsBindThroughApplicationYml() throws IOException {
        var raised = bindLikeContainer(Map.of("CHAT_MAX_BODY_BYTES", "1048576", "CHAT_MAX_CALLS", "24",
                "CHAT_MAX_TOOL_CALLS", "60", "CHAT_ATTEMPT_TOKEN_BUDGET", "600000", "CHAT_EXECUTION_TIMEOUT", "300s"));

        assertThat(raised.maxBodyBytes()).isEqualTo(1_048_576);
        assertThat(raised.maxCalls()).isEqualTo(24);
        assertThat(raised.maxToolCalls()).isEqualTo(60);
        assertThat(raised.attemptTokenBudget()).isEqualTo(600_000);
        assertThat(raised.executionTimeout()).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    @DisplayName("환경변수가 없으면 application.yml·운영 Compose·예제 환경파일 모두 기존 기본값을 쓴다")
    void deploymentDefaultsStayUnchanged() throws IOException {
        assertDefaultLimits(bindLikeContainer(Map.of()));

        var defaults = Map.of("CHAT_MAX_BODY_BYTES", "131072", "CHAT_MAX_CALLS", "8", "CHAT_MAX_TOOL_CALLS", "20",
                "CHAT_ATTEMPT_TOKEN_BUDGET", "240000", "CHAT_EXECUTION_TIMEOUT", "120s");
        var compose = new YamlPropertySourceLoader()
                .load("compose.api.prod.yml", new FileSystemResource("deploy/compose.api.prod.yml")).getFirst();
        var example = Files.readAllLines(Path.of("deploy/api.env.example"));
        defaults.forEach((name, value) -> {
            // 운영 컨테이너는 api.env의 값을 그대로 넘기고, 값이 없을 때만 기본값을 쓴다.
            assertThat(compose.getProperty("services.backend.environment." + name))
                    .as("운영 Compose의 %s", name).hasToString("${" + name + ":-" + value + "}");
            assertThat(example).as("api.env.example의 %s", name).contains(name + "=" + value);
        });
    }

    private void assertDefaultLimits(ChatProperties properties) {
        assertThat(properties.maxBodyBytes()).isEqualTo(131_072);
        assertThat(properties.maxCalls()).isEqualTo(8);
        assertThat(properties.maxToolCalls()).isEqualTo(20);
        assertThat(properties.attemptTokenBudget()).isEqualTo(240_000);
        assertThat(properties.executionTimeout()).isEqualTo(Duration.ofSeconds(120));
    }

    /** 컨테이너 환경변수와 실제 application.yml만으로 자리표시자 해석을 거쳐 바인딩한다. .env는 읽지 않는다. */
    private static ChatProperties bindLikeContainer(Map<String, String> containerEnvironment) throws IOException {
        var environment = new MockEnvironment();
        containerEnvironment.forEach(environment::setProperty);
        new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment).bindOrCreate("chat", ChatProperties.class);
    }

    private ChatProperties bodyLimit(int maxBodyBytes) {
        return new ChatProperties(false, false, null, null,
                maxBodyBytes, 0, 0, 0, 0, 0, 0, 0, null, null, null);
    }

    private ChatProperties properties(boolean enabled, boolean operations, String url, String secret) {
        return new ChatProperties(enabled, operations, url, secret,
                0, 0, 0, 0, 0, 0, 0, 0, null, null, null);
    }
}
