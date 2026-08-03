package org.monitoring.catchholebackend.domain.auth.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.exception.AuthErrorCode;
import org.monitoring.catchholebackend.global.config.phoneverification.SmsProperties;
import org.monitoring.catchholebackend.global.exception.AppException;

@DisplayName("SOLAPI SMS 발송기")
class SolapiSmsSenderTest {

    private static final Instant INSTANT = Instant.parse("2026-08-03T00:00:00Z");
    private static final String DATE_TIME = "2026-08-03T00:00:00Z";
    private static final String SALT = "0123456789abcdef";

    @Test
    @DisplayName("요청 시각과 salt를 API secret 기반 HMAC-SHA256 hex 서명으로 만든다")
    void createsSolapiSignature() {
        String signature = SolapiSmsSender.createSignature(DATE_TIME, SALT, "api-secret");

        assertThat(signature).isEqualTo("76b9de921f4d3d474aef59609e5a2e3e3ab0dad0e05169854be15adf2f58846b");
    }

    @Test
    @DisplayName("정상 접수 응답을 성공으로 처리하고 SOLAPI 인증 헤더와 SMS body를 전송한다")
    void sendsExpectedRequestAndAcceptsRegisteredMessage() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(successResponse());
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        SolapiSmsSender sender = sender(httpClient);

        HttpRequest request = sender.createRequest("01012345678", "654321", INSTANT, SALT);
        sender.sendVerificationCode("01012345678", "654321");

        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().toString()).isEqualTo(SolapiSmsSender.API_URL);
        assertThat(request.headers().firstValue("Authorization"))
                .hasValueSatisfying(value -> assertThat(value)
                        .contains("HMAC-SHA256 apiKey=api-key, date=" + DATE_TIME + ", salt=" + SALT)
                        .contains("signature=76b9de921f4d3d474aef59609e5a2e3e3ab0dad0e05169854be15adf2f58846b"));

        JsonNode body = new ObjectMapper().readTree(readBody(request));
        JsonNode message = body.path("messages").get(0);
        assertThat(body.path("strict").asBoolean()).isTrue();
        assertThat(body.path("showMessageList").asBoolean()).isTrue();
        assertThat(message.path("type").asText()).isEqualTo("SMS");
        assertThat(message.path("autoTypeDetect").asBoolean()).isFalse();
        assertThat(message.path("from").asText()).isEqualTo("01099998888");
        assertThat(message.path("to").asText()).isEqualTo("01012345678");
        assertThat(message.path("text").asText()).contains("654321");
        assertThat(message.path("text").asText().getBytes(Charset.forName("EUC-KR")).length)
                .isLessThanOrEqualTo(90);
    }

    @Test
    @DisplayName("HTTP 성공이어도 메시지가 정상 접수되지 않으면 가용성 오류로 변환하고 재시도하지 않는다")
    void rejectsUnregisteredMessageWithoutRetry() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "groupInfo": {
                    "count": {"registeredSuccess": 0, "registeredFailed": 1}
                  },
                  "failedMessageList": [{"statusCode": "3040"}],
                  "messageList": []
                }
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        assertThatThrownBy(() -> sender(httpClient).sendVerificationCode("01012345678", "123456"))
                .isInstanceOf(AppException.class)
                .extracting("resultCode")
                .isEqualTo(AuthErrorCode.AUTH_PHONE_VERIFICATION_UNAVAILABLE);
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @DisplayName("2xx가 아닌 응답은 가용성 오류로 변환하고 재시도하지 않는다")
    void rejectsHttpErrorWithoutRetry() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        assertThatThrownBy(() -> sender(httpClient).sendVerificationCode("01012345678", "123456"))
                .isInstanceOf(AppException.class)
                .extracting("resultCode")
                .isEqualTo(AuthErrorCode.AUTH_PHONE_VERIFICATION_UNAVAILABLE);
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private SolapiSmsSender sender(HttpClient httpClient) {
        SmsProperties properties = new SmsProperties(
                "solapi",
                new SmsProperties.Solapi("api-key", "api-secret", "01099998888")
        );
        return new SolapiSmsSender(
                httpClient,
                Clock.fixed(INSTANT, ZoneOffset.UTC),
                properties
        );
    }

    private String successResponse() {
        return """
                {
                  "groupInfo": {
                    "count": {"registeredSuccess": 1, "registeredFailed": 0}
                  },
                  "failedMessageList": [],
                  "messageList": [{"statusCode": "2000"}]
                }
                """;
    }

    private String readBody(HttpRequest request) {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CompletableFuture<String> result = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.complete(output.toString(StandardCharsets.UTF_8));
            }
        });
        return result.join();
    }
}
