package org.monitoring.catchholebackend.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "휴대폰 인증번호 발송 응답")
public record PhoneVerificationSendResponse(
        @Schema(description = "인증번호 확인에 사용할 흐름 식별자", example = "verification-id-example")
        String verificationId,

        @Schema(description = "인증번호 만료까지 남은 초", example = "300")
        long expiresInSeconds,

        @Schema(description = "재전송 가능 시점까지 남은 초", example = "60")
        long resendAfterSeconds
) {
}
