package org.monitoring.catchholebackend.domain.auth.email;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.monitoring.catchholebackend.global.config.emailverification.EmailVerificationProperties;
import org.springframework.stereotype.Component;

@Component
public class EmailVerificationHasher {

    private static final String HMAC_SHA_256 = "HmacSHA256";
    private final byte[] secret;

    public EmailVerificationHasher(EmailVerificationProperties properties) {
        this.secret = properties.hashSecret() == null ? new byte[0]
                : properties.hashSecret().getBytes(StandardCharsets.UTF_8);
    }

    public String hashIdentifier(String value) {
        return generateHmac("identifier:" + value);
    }

    public String hashVerificationCode(String verificationId, String verificationCode) {
        return generateHmac("code:" + verificationId + ":" + verificationCode);
    }

    private String generateHmac(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(secret, HMAC_SHA_256));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("이메일 인증 HMAC을 생성할 수 없습니다.", exception);
        }
    }
}
