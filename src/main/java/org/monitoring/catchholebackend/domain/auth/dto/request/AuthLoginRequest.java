package org.monitoring.catchholebackend.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "로그인 요청")
public record AuthLoginRequest(
        @Schema(description = "가입한 이메일", example = "user@example.com")
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @Schema(description = "가입 시 설정한 비밀번호", example = "password123!", format = "password")
        @NotBlank(message = "비밀번호는 필수입니다.")
        String password
) {
}
