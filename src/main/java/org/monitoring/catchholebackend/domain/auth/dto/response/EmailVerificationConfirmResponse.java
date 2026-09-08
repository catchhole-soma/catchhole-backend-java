package org.monitoring.catchholebackend.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "이메일 인증 완료 응답")
public record EmailVerificationConfirmResponse(
        @Schema(
                description = "회원가입 요청에 사용할 1회용 인증 토큰",
                example = "email-verification-token-example"
        )
        String emailVerificationToken,

        @Schema(description = "회원가입 인증 토큰 만료까지 남은 초", example = "600")
        long expiresInSeconds
) {
}
