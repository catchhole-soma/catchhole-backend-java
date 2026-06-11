package org.monitoring.catchholebackend.domain.member.mapper;

import org.monitoring.catchholebackend.domain.member.dto.response.MemberResponse;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.springframework.stereotype.Component;

@Component
public class MemberMapper {

    public MemberResponse toResponse(Member member) {
        return new MemberResponse(
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
}
