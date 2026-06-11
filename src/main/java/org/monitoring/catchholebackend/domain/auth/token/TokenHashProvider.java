package org.monitoring.catchholebackend.domain.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.exception.CommonErrorCode;
import org.springframework.stereotype.Component;

@Component
public class TokenHashProvider {

    public String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new AppException(CommonErrorCode.COMMON_INTERNAL_SERVER_ERROR, exception);
        }
    }
}
