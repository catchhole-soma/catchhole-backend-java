package org.monitoring.catchholebackend.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "액세스 토큰 발급 응답")
public record AuthTokenResponse(
        @Schema(description = "API 인증에 사용할 JWT 액세스 토큰", example = "eyJhbGciOiJIUzI1NiJ9...")
        String accessToken,

        @Schema(description = "Authorization 헤더에 사용할 토큰 타입", example = "Bearer")
        String tokenType,

        @Schema(description = "액세스 토큰 만료까지 남은 시간(초)", example = "1800")
        long expiresIn
) {
    public static AuthTokenResponse bearer(String accessToken, long expiresIn) {
        return new AuthTokenResponse(accessToken, "Bearer", expiresIn);
    }
}
