package org.monitoring.catchholebackend.domain.auth.mapper;

import org.monitoring.catchholebackend.domain.auth.dto.request.AuthSignupRequest;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.springframework.stereotype.Component;

@Component
public class AuthMapper {

    public Member toEntity(
            AuthSignupRequest request,
            String passwordHash,
            String verifiedPhoneNumber
    ) {
        return Member.registerPhoneVerified(
                request.email(),
                passwordHash,
                verifiedPhoneNumber,
                request.displayName()
        );
    }
}
