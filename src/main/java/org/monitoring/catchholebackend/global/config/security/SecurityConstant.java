package org.monitoring.catchholebackend.global.config.security;

import java.util.Arrays;
import java.util.stream.Stream;

public final class SecurityConstant {

    private SecurityConstant() {
    }

    public static final String REFRESH_TOKEN_COOKIE_PATH = "/api/v1/auth";

    // 도메인별 공개 경로 (인증 불필요)
    // 도메인 추가 시 카테고리 주석과 함께 여기에 등록한다.
    public static final String[] PUBLIC_AUTH_URLS = {
            "/api/v1/auth/signup",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout"
    };

    // Swagger UI 관련 공개 경로
    public static final String[] SWAGGER_URLS = {
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    // Actuator 모니터링 공개 경로
    public static final String[] ACTUATOR_URLS = {
            "/actuator/health",
            "/actuator/prometheus"
    };

    // ROLE_ADMIN 권한 필요 경로
    public static final String[] ADMIN_URLS = {
    };

    // 모든 공개 URL 통합 (SecurityConfig에서 permitAll에 사용)
    public static final String[] PUBLIC_URLS = Stream.of(
                    PUBLIC_AUTH_URLS,
                    SWAGGER_URLS,
                    ACTUATOR_URLS
            )
            .flatMap(Arrays::stream)
            .toArray(String[]::new);
}
