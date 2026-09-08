package org.monitoring.catchholebackend.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.monitoring.catchholebackend.domain.auth.email.EmailAddressNormalizer;

@Schema(description = "이메일 인증번호 발송 요청")
public record EmailVerificationSendRequest(
        @Schema(description = "회원가입에 사용할 이메일", example = "writer@example.com", maxLength = 255)
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "올바른 이메일 형식이어야 합니다.")
        @Size(max = 255, message = "이메일은 255자 이하여야 합니다.")
        String email
) {
    public EmailVerificationSendRequest {
        email = EmailAddressNormalizer.normalize(email);
    }
}
