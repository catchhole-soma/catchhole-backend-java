package org.monitoring.catchholebackend.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "휴대폰 인증번호 확인 요청")
public record PhoneVerificationConfirmRequest(
        @Schema(description = "SMS로 수신한 6자리 인증번호", example = "123456")
        @NotBlank(message = "인증번호는 필수입니다.")
        @Pattern(regexp = "^\\d{6}$", message = "인증번호는 6자리 숫자여야 합니다.")
        String code
) {
}
