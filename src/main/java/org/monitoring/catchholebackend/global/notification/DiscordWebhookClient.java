package org.monitoring.catchholebackend.global.notification;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** 예외·응답 본문·웹훅 URL을 로그나 호출자에게 전달하지 않는다. */
public class DiscordWebhookClient implements AutoCloseable {
    public record Delivery(boolean delivered, Duration pause) {}
    private final URI uri;
    private final HttpClient client;
    private final JsonMapper json = new JsonMapper();

    public DiscordWebhookClient(URI uri) {
        this.uri = uri;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Delivery send(String content) {
        try {
            String body = json.writeValueAsString(Map.of("content", content,
                    "allowed_mentions", Map.of("parse", List.of())));
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            Duration retry = seconds(response.headers().firstValue("Retry-After").orElse(null));
            if (retry.isZero() && "0".equals(response.headers().firstValue("X-RateLimit-Remaining").orElse(null))) {
                retry = seconds(response.headers().firstValue("X-RateLimit-Reset-After").orElse(null));
            }
            if (status >= 200 && status < 300) return new Delivery(true, retry);
            if (status == 429) return new Delivery(false, retry.isZero() ? Duration.ofMinutes(1) : retry);
            if (status >= 400 && status < 500) return new Delivery(false, Duration.ofHours(1));
            return new Delivery(false, retry);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Delivery(false, Duration.ofMinutes(1));
        } catch (IOException | RuntimeException ex) {
            return new Delivery(false, Duration.ZERO);
        }
    }

    private Duration seconds(String value) {
        try {
            double seconds = Double.parseDouble(value);
            if (!Double.isFinite(seconds) || seconds <= 0) return Duration.ZERO;
            // 헤더가 요구한 대기보다 일찍 보내지 않도록 밀리초를 올림한다.
            return Duration.ofMillis((long) Math.ceil(seconds * 1000));
        } catch (RuntimeException ex) { return Duration.ZERO; }
    }

    @Override public void close() { client.shutdownNow(); }
}
