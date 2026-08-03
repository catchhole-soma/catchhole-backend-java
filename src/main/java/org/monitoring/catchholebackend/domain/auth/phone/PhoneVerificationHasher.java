package org.monitoring.catchholebackend.domain.auth.phone;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.monitoring.catchholebackend.global.config.phoneverification.PhoneVerificationProperties;
import org.springframework.stereotype.Component;

@Component
public class PhoneVerificationHasher {

    private static final String HMAC_SHA_256 = "HmacSHA256";
    private final byte[] secret;

    public PhoneVerificationHasher(PhoneVerificationProperties properties) {
        this.secret = properties.hashSecret().getBytes(StandardCharsets.UTF_8);
    }

    public String identifier(String value) {
        return hmac("identifier:" + value);
    }

    public String code(String verificationId, String code) {
        return hmac("code:" + verificationId + ":" + code);
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(secret, HMAC_SHA_256));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("휴대폰 인증 HMAC을 생성할 수 없습니다.", exception);
        }
    }
}
