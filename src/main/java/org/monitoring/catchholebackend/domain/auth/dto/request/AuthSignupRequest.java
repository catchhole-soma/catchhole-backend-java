package org.monitoring.catchholebackend.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Schema(description = "회원가입 요청")
public record AuthSignupRequest(
        @Schema(description = "로그인 ID로 사용할 이메일", example = "user@example.com")
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @Schema(description = "영문과 숫자를 포함한 8자 이상 64자 이하의 비밀번호", example = "password123!", format = "password")
        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, max = 64, message = "비밀번호는 8자 이상 64자 이하로 입력해주세요.")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).*$", message = "비밀번호는 영문과 숫자를 각각 하나 이상 포함해야 합니다.")
        String password,

        @Schema(description = "서비스 화면에 표시할 사용자 이름", example = "장은호")
        @NotBlank(message = "표시 이름은 필수입니다.")
        @Size(max = 20, message = "표시 이름은 20자 이하로 입력해주세요.")
        String displayName,

        @Schema(
                description = "현재 이용약관 필수 동의 여부",
                example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @AssertTrue(message = "이용약관에 동의해야 합니다.")
        boolean termsAccepted,

        @Schema(
                description = "현재 개인정보처리방침 필수 확인 여부",
                example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @AssertTrue(message = "개인정보처리방침을 확인해야 합니다.")
        boolean privacyPolicyAcknowledged,

        @Schema(
                description = "만 14세 이상 확인 여부",
                example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @AssertTrue(message = "만 14세 이상만 가입할 수 있습니다.")
        boolean age14OrOlderConfirmed,

        @Schema(description = "화면에 표시한 현재 이용약관 문서 식별자", example = "3")
        @NotNull(message = "이용약관 문서 식별자는 필수입니다.")
        @Positive(message = "이용약관 문서 식별자는 양수여야 합니다.")
        Long termsDocumentId,

        @Schema(description = "화면에 표시한 현재 개인정보처리방침 문서 식별자", example = "4")
        @NotNull(message = "개인정보처리방침 문서 식별자는 필수입니다.")
        @Positive(message = "개인정보처리방침 문서 식별자는 양수여야 합니다.")
        Long privacyPolicyDocumentId,

        @Schema(description = "휴대폰 인증 완료 후 발급된 1회용 회원가입 토큰", example = "4Kd7...Q2")
        @NotBlank(message = "휴대폰 인증 토큰은 필수입니다.")
        String phoneVerificationToken
) {
}
