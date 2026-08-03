package org.monitoring.catchholebackend.domain.auth.sms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.monitoring.catchholebackend.domain.auth.exception.AuthErrorCode;
import org.monitoring.catchholebackend.global.config.phoneverification.SmsProperties;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(prefix = "sms", name = "provider", havingValue = "solapi")
public class SolapiSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(SolapiSmsSender.class);

    static final String API_URL = "https://api.solapi.com/messages/v4/send-many/detail";
    static final String MESSAGE_TEMPLATE = "[CatchHole] 인증번호 %s (5분 내 입력)";
    private static final String HMAC_SHA_256 = "HmacSHA256";
    private static final String ACCEPTED_STATUS_CODE = "2000";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SmsProperties.Solapi properties;

    public SolapiSmsSender(
            HttpClient httpClient,
            Clock phoneVerificationClock,
            SmsProperties smsProperties
    ) {
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        this.clock = phoneVerificationClock;
        this.properties = smsProperties.solapi();
        validateProperties(this.properties);
    }

    @Override
    public void sendVerificationCode(String phoneNumber, String code) {
        try {
            HttpRequest request = createRequest(
                    phoneNumber,
                    code,
                    clock.instant(),
                    UUID.randomUUID().toString().replace("-", "")
            );
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (!isAccepted(response)) {
                log.warn("SOLAPI SMS request rejected. status={}", response.statusCode());
                throw new AppException(AuthErrorCode.AUTH_PHONE_VERIFICATION_UNAVAILABLE);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("SOLAPI SMS request interrupted.");
            throw new AppException(AuthErrorCode.AUTH_PHONE_VERIFICATION_UNAVAILABLE, exception);
        } catch (IOException exception) {
            log.warn("SOLAPI SMS request failed.");
            throw new AppException(AuthErrorCode.AUTH_PHONE_VERIFICATION_UNAVAILABLE, exception);
        }
    }

    HttpRequest createRequest(String phoneNumber, String code, Instant instant, String salt) {
        String dateTime = instant.toString();
        String body = writeBody(phoneNumber, code);
        return HttpRequest.newBuilder(URI.create(API_URL))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", createAuthorizationHeader(dateTime, salt))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    static String createSignature(String dateTime, String salt, String apiSecret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256));
            byte[] signature = mac.doFinal((dateTime + salt).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(signature);
        } catch (Exception exception) {
            throw new IllegalStateException("SOLAPI 서명을 생성할 수 없습니다.", exception);
        }
    }

    private String createAuthorizationHeader(String dateTime, String salt) {
        return "HMAC-SHA256 apiKey=%s, date=%s, salt=%s, signature=%s".formatted(
                properties.apiKey(),
                dateTime,
                salt,
                createSignature(dateTime, salt, properties.apiSecret())
        );
    }

    private String writeBody(String phoneNumber, String code) {
        Map<String, Object> message = Map.of(
                "to", phoneNumber,
                "from", properties.senderNumber(),
                "text", MESSAGE_TEMPLATE.formatted(code),
                "type", "SMS",
                "country", "82",
                "autoTypeDetect", false
        );
        Map<String, Object> body = Map.of(
                "messages", List.of(message),
                "strict", true,
                "allowDuplicates", false,
                "showMessageList", true
        );
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("SOLAPI 요청 body를 생성할 수 없습니다.", exception);
        }
    }

    private boolean isAccepted(HttpResponse<String> response) throws JsonProcessingException {
        if (response.statusCode() < 200 || response.statusCode() >= 300
                || !StringUtils.hasText(response.body())) {
            return false;
        }
        JsonNode body = objectMapper.readTree(response.body());
        JsonNode count = body.path("groupInfo").path("count");
        JsonNode messageList = body.path("messageList");
        return count.path("registeredSuccess").asInt(-1) == 1
                && count.path("registeredFailed").asInt(-1) == 0
                && messageList.isArray()
                && messageList.size() == 1
                && ACCEPTED_STATUS_CODE.equals(messageList.get(0).path("statusCode").asText());
    }

    private void validateProperties(SmsProperties.Solapi solapi) {
        if (solapi == null
                || !StringUtils.hasText(solapi.apiKey())
                || !StringUtils.hasText(solapi.apiSecret())
                || !StringUtils.hasText(solapi.senderNumber())) {
            throw new IllegalStateException("SOLAPI provider 설정값이 모두 필요합니다.");
        }
    }
}
