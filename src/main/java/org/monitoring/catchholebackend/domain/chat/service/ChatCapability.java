package org.monitoring.catchholebackend.domain.chat.service;

import static org.monitoring.catchholebackend.domain.chat.exception.ChatErrorCode.*;

import lombok.RequiredArgsConstructor;

import org.monitoring.catchholebackend.domain.chat.entity.ChatRun;
import org.monitoring.catchholebackend.global.config.chat.ChatProperties;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Component
@RequiredArgsConstructor
public class ChatCapability {
    private final ChatProperties properties;

    public String issue(ChatRun run) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(
                    new SecretKeySpec(
                            properties.capabilitySecret().getBytes(StandardCharsets.UTF_8),
                            "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(
                            mac.doFinal(
                                    ("chat-v1:" + run.getId() + ":" + run.getWork().getId())
                                            .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("실행 인증 설정 오류");
        }
    }

    public void check(ChatRun run, String token) {
        if (token == null
                || !MessageDigest.isEqual(
                        issue(run).getBytes(StandardCharsets.US_ASCII),
                        token.getBytes(StandardCharsets.US_ASCII)))
            throw new AppException(CHAT_SCOPE_INVALID);
    }

    public String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
