package org.monitoring.catchholebackend.domain.member.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.member.type.MemberRole;
import org.monitoring.catchholebackend.domain.member.type.MemberStatus;

@Schema(description = "회원 응답")
public record MemberResponse(
        @Schema(description = "서비스 내부 회원 ID", example = "1")
        Long id,

        @Schema(description = "회원 이메일", example = "user@example.com")
        String email,

        @Schema(description = "하이픈 없는 회원 휴대폰 번호", example = "01012345678")
        String phoneNumber,

        @Schema(description = "휴대폰 인증 완료 여부", example = "false")
        boolean phoneVerified,

        @Schema(description = "서비스 화면에 표시할 사용자 이름", example = "장은호")
        String displayName,

        @Schema(description = "프로필 이미지 URL", example = "https://example.com/profile.png", nullable = true)
        String profileImageUrl,

        @Schema(description = "계정 상태", example = "ACTIVE")
        MemberStatus status,

        @Schema(description = "회원 권한", example = "AUTHOR")
        MemberRole role
) {

    public static MemberResponse from(MemberPrincipal member) {
        return new MemberResponse(
                member.memberId(),
                member.email(),
                member.phoneNumber(),
                member.phoneVerified(),
                member.displayName(),
                member.profileImageUrl(),
                member.status(),
                member.role()
        );
    }
}
