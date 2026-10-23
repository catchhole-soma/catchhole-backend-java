package org.monitoring.catchholebackend.global.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.global.config.analysisnotification.AnalysisNotificationProperties;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Discord 알림 HTTP 전송")
class DiscordWebhookClientTest {
    HttpServer server;
    DiscordWebhookClient client;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = new DiscordWebhookClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/webhook?wait=true"));
    }
    @AfterEach void close() { client.close(); server.stop(0); }

    @Test
    @DisplayName("메시지를 올바른 JSON으로 보내고 모든 자동 멘션을 비활성화한다")
    void sendsJsonWithoutMentions() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/webhook", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            query.set(exchange.getRequestURI().getQuery());
            exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        assertThat(client.send("회차 실패\n\"인용\" @everyone").delivered()).isTrue();
        var payload = new JsonMapper().readTree(body.get());
        assertThat(payload.get("content").asString()).isEqualTo("회차 실패\n\"인용\" @everyone");
        assertThat(payload.get("allowed_mentions").get("parse").isEmpty()).isTrue();
        assertThat(query.get()).isEqualTo("wait=true");
    }

    @Test
    @DisplayName("429 응답의 소수 초 Retry-After를 보존한다")
    void honorsRateLimit() {
        server.createContext("/webhook", exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "2.125");
            exchange.sendResponseHeaders(429, -1); exchange.close();
        });
        var result = client.send("실패 알림");
        assertThat(result.delivered()).isFalse();
        assertThat(result.pause()).isEqualTo(Duration.ofMillis(2125));
    }

    @Test
    @DisplayName("정상 전송도 잔여 한도가 0이면 다음 요청까지 대기한다")
    void honorsSuccessfulRateLimitResponse() {
        server.createContext("/webhook", exchange -> {
            exchange.getResponseHeaders().add("X-RateLimit-Remaining", "0");
            exchange.getResponseHeaders().add("X-RateLimit-Reset-After", "1.5");
            exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        var result = client.send("실패 알림");
        assertThat(result.delivered()).isTrue();
        assertThat(result.pause()).isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    @DisplayName("서버 오류와 연결 실패는 분석 요청으로 전파하지 않는다")
    void isolatesTransportFailures() {
        server.createContext("/webhook", exchange -> { exchange.sendResponseHeaders(503, -1); exchange.close(); });
        assertThat(client.send("실패 알림").delivered()).isFalse();
        server.stop(0);
        assertThat(client.send("실패 알림").delivered()).isFalse();
    }

    @Test
    @DisplayName("외부 호스트로 redirect하지 않고 잘못된 웹훅은 긴 간격으로 재시도한다")
    void doesNotFollowRedirectAndBacksOffInvalidWebhook() {
        server.createContext("/webhook", exchange -> {
            exchange.getResponseHeaders().add("Location", "https://example.invalid/secret");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        assertThat(client.send("실패 알림").delivered()).isFalse();
        server.removeContext("/webhook");
        server.createContext("/webhook", exchange -> { exchange.sendResponseHeaders(404, -1); exchange.close(); });
        assertThat(client.send("실패 알림").pause()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("웹훅 설정은 Discord HTTPS만 허용하고 오류에 비밀 URL을 노출하지 않는다")
    void validatesConfigurationWithoutExposingSecrets() {
        var properties = new AnalysisNotificationProperties();
        properties.setDiscordWebhookUrl("https://discord.com/api/webhooks/123/test-token");
        assertThat(properties.webhookUri().getQuery()).isEqualTo("wait=true");
        for (String invalid : new String[] {"", "http://discord.com/api/webhooks/123/test-token",
                "https://example.test/api/webhooks/123/test-token", "https://discord.com/api/webhooks/123/test-token?secret=1",
                "https://discord.com/api/webhooks/123/test-token#secret", "invalid secret"}) {
            properties.setDiscordWebhookUrl(invalid);
            assertThatThrownBy(properties::webhookUri).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("분석 알림용 Discord 웹훅 설정이 올바르지 않습니다.").hasNoCause();
        }
    }
}
