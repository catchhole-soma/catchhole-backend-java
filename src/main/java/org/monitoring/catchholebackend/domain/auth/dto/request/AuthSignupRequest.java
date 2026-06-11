package org.monitoring.catchholebackend.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "회원가입 요청")
public record AuthSignupRequest(
        @Schema(description = "로그인 ID로 사용할 이메일", example = "user@example.com")
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @Schema(description = "8자 이상 72자 이하의 비밀번호", example = "password123!", format = "password")
        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하로 입력해주세요.")
        String password,

        @Schema(description = "하이픈 없이 010으로 시작하는 11자리 휴대폰 번호", example = "01012345678")
        @NotBlank(message = "휴대폰 번호는 필수입니다.")
        @Pattern(regexp = "^010\\d{8}$", message = "휴대폰 번호는 하이픈 없이 010으로 시작하는 11자리 숫자여야 합니다.")
        String phoneNumber,

        @Schema(description = "서비스 화면에 표시할 사용자 이름", example = "장은호")
        @NotBlank(message = "표시 이름은 필수입니다.")
        @Size(max = 50, message = "표시 이름은 50자 이하로 입력해주세요.")
        String displayName
) {
}
