package org.monitoring.catchholebackend.domain.member.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "회원 즉시 탈퇴 요청")
public record MemberWithdrawalCreateRequest(
        @Schema(
                description = "탈퇴 요청을 재확인하기 위한 현재 비밀번호",
                example = "password123",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "현재 비밀번호를 입력해주세요.")
        @Size(max = 64, message = "현재 비밀번호는 64자 이하여야 합니다.")
        String currentPassword,

        @Schema(
                description = "복구할 수 없는 탈퇴임을 확인하기 위한 고정 문구",
                example = "회원 탈퇴",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "회원 탈퇴 확인 문구를 입력해주세요.")
        @Pattern(regexp = "회원 탈퇴", message = "회원 탈퇴 확인 문구가 일치하지 않습니다.")
        String confirmation
) {
}
