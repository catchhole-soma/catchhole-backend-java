package org.monitoring.catchholebackend.domain.auth.token;

import org.monitoring.catchholebackend.global.config.auth.AuthProperties;
import org.monitoring.catchholebackend.global.config.security.SecurityConstant;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenCookieFactory {

    public static final String COOKIE_NAME = "refreshToken";

    private final AuthProperties authProperties;

    public RefreshTokenCookieFactory(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    public ResponseCookie create(String refreshToken) {
        return baseCookie(refreshToken)
                .maxAge(authProperties.refreshTokenExpiration())
                .build();
    }

    public ResponseCookie delete() {
        return baseCookie("")
                .maxAge(0)
                .build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(authProperties.cookie().secure())
                .sameSite(authProperties.cookie().sameSite())
                .path(SecurityConstant.REFRESH_TOKEN_COOKIE_PATH);
    }
}
