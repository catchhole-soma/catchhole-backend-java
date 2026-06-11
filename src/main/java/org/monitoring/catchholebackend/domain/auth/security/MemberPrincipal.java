package org.monitoring.catchholebackend.domain.auth.security;

import java.util.Collection;
import java.util.List;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.entity.MemberRole;
import org.monitoring.catchholebackend.domain.member.entity.MemberStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public record MemberPrincipal(
        Long memberId,
        String email,
        String phoneNumber,
        boolean phoneVerified,
        String displayName,
        String profileImageUrl,
        MemberStatus status,
        MemberRole role
) {

    public static MemberPrincipal from(Member member) {
        return new MemberPrincipal(
                member.getId(),
                member.getEmail(),
                member.getPhoneNumber(),
                member.isPhoneVerified(),
                member.getDisplayName(),
                member.getProfileImageUrl(),
                member.getStatus(),
                member.getRole()
        );
    }

    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}
