package org.monitoring.catchholebackend.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "휴대폰 인증번호 발송 요청")
public record PhoneVerificationSendRequest(
        @Schema(description = "하이픈 없이 010으로 시작하는 11자리 휴대폰 번호", example = "01012345678")
        @NotBlank(message = "휴대폰 번호는 필수입니다.")
        @Pattern(regexp = "^010\\d{8}$", message = "휴대폰 번호는 하이픈 없이 010으로 시작하는 11자리 숫자여야 합니다.")
        String phoneNumber
) {
}
