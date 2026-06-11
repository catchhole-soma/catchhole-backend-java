package org.monitoring.catchholebackend.domain.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.entity.MemberRole;
import org.monitoring.catchholebackend.domain.member.entity.MemberStatus;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class JwtMemberAuthenticationConverterTest {

    @Mock
    private MemberRepository memberRepository;

    @Test
    void convertsJwtSubjectToMemberPrincipal() {
        Member member = member(MemberRole.ADMIN, MemberStatus.ACTIVE);
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        JwtMemberAuthenticationConverter converter = new JwtMemberAuthenticationConverter(memberRepository);

        var authentication = converter.convert(jwt("1"));

        assertThat(authentication.getPrincipal()).isInstanceOf(MemberPrincipal.class);
        MemberPrincipal principal = (MemberPrincipal) authentication.getPrincipal();
        assertThat(principal.memberId()).isEqualTo(1L);
        assertThat(principal.email()).isEqualTo("writer@example.com");
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void rejectsJwtWhenMemberIsMissing() {
        when(memberRepository.findById(1L)).thenReturn(Optional.empty());
        JwtMemberAuthenticationConverter converter = new JwtMemberAuthenticationConverter(memberRepository);

        assertThatThrownBy(() -> converter.convert(jwt("1")))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    private Jwt jwt(String subject) {
        return new Jwt(
                "access-token",
                Instant.now(),
                Instant.now().plusSeconds(1800),
                Map.of("alg", "HS256"),
                Map.of("sub", subject)
        );
    }

    private Member member(MemberRole role, MemberStatus status) {
        Member member = Member.register("writer@example.com", "encoded-password", "01012345678", "작가");
        ReflectionTestUtils.setField(member, "id", 1L);
        ReflectionTestUtils.setField(member, "role", role);
        ReflectionTestUtils.setField(member, "status", status);
        return member;
    }
}
