package org.monitoring.catchholebackend.domain.auth.service;

import org.monitoring.catchholebackend.domain.auth.dto.request.AuthLoginRequest;
import org.monitoring.catchholebackend.domain.auth.dto.request.AuthSignupRequest;
import org.monitoring.catchholebackend.domain.member.dto.response.MemberResponse;

public interface AuthService {

    MemberResponse signup(AuthSignupRequest request);

    AuthTokenIssueResult login(AuthLoginRequest request);

    AuthTokenIssueResult refresh(String refreshToken);

    void logout(String refreshToken);
}
