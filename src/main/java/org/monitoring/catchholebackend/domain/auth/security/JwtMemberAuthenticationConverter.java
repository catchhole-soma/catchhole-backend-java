package org.monitoring.catchholebackend.domain.auth.security;

import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

@RequiredArgsConstructor
public class JwtMemberAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final MemberRepository memberRepository;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Long memberId = parseMemberId(jwt.getSubject());
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> invalidToken("토큰의 회원 정보를 찾을 수 없습니다."));
        if (!member.isActive()) {
            throw invalidToken("비활성화된 회원의 토큰입니다.");
        }

        MemberPrincipal principal = MemberPrincipal.from(member);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                principal.getAuthorities()
        );
        authentication.setDetails(jwt);
        return authentication;
    }

    private Long parseMemberId(String subject) {
        if (!StringUtils.hasText(subject)) {
            throw invalidToken("JWT의 subject 클레임이 비어 있습니다.");
        }
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException exception) {
            throw invalidToken("JWT의 subject 클레임이 회원 ID 형식이 아닙니다.");
        }
    }

    private OAuth2AuthenticationException invalidToken(String description) {
        return new OAuth2AuthenticationException(
                new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null)
        );
    }
}
