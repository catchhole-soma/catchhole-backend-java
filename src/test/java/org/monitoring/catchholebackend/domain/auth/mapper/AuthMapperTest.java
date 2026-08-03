package org.monitoring.catchholebackend.domain.auth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.dto.request.AuthSignupRequest;
import org.monitoring.catchholebackend.domain.member.entity.Member;

@DisplayName("인증 Mapper 단위 테스트")
class AuthMapperTest {

    private final AuthMapper authMapper = new AuthMapper();

    @Test
    @DisplayName("인증된 전화번호와 암호화한 비밀번호로 회원가입 Entity를 조립한다")
    void toEntityCreatesPhoneVerifiedMember() {
        AuthSignupRequest request = new AuthSignupRequest(
                "writer@example.com",
                "password123",
                "작가",
                "phone-verification-token"
        );

        Member member = authMapper.toEntity(request, "encoded-password", "01012345678");

        assertThat(member.getEmail()).isEqualTo("writer@example.com");
        assertThat(member.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(member.getPhoneNumber()).isEqualTo("01012345678");
        assertThat(member.getDisplayName()).isEqualTo("작가");
        assertThat(member.isPhoneVerified()).isTrue();
    }
}
