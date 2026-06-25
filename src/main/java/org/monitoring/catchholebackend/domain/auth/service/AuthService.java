package org.monitoring.catchholebackend.domain.auth.service;

import org.monitoring.catchholebackend.domain.auth.dto.request.AuthLoginRequest;
import org.monitoring.catchholebackend.domain.auth.dto.request.AuthSignupRequest;
import org.monitoring.catchholebackend.domain.member.dto.response.MemberResponse;

public interface AuthService {

    /**
     * 회원가입 요청의 이메일과 휴대폰 번호 중복을 검증하고 새 회원을 등록한다.
     * 비밀번호는 해시로 저장하며, 회원가입 단계에서는 access/refresh token을 발급하지 않는다.
     */
    MemberResponse signup(AuthSignupRequest request);

    /**
     * 이메일과 비밀번호, 회원 활성 상태를 검증한 뒤 access token과 refresh token을 발급한다.
     * refresh token은 원문을 응답 쿠키로 전달할 수 있도록 결과에 포함하고, 서버에는 해시만 저장한다.
     */
    AuthTokenIssueResult login(AuthLoginRequest request);

    /**
     * refresh token 원문으로 저장된 토큰을 찾고 폐기 여부, 만료 여부, 회원 활성 상태를 검증한다.
     * 검증된 기존 refresh token은 즉시 폐기하고 새 access token과 refresh token을 재발급한다.
     */
    AuthTokenIssueResult refresh(String refreshToken);

    /**
     * 전달된 refresh token이 있으면 저장된 토큰을 찾아 폐기해 이후 재사용을 막는다.
     * 토큰이 없거나 이미 저장소에 없더라도 로그아웃 요청은 오류 없이 끝낸다.
     */
    void logout(String refreshToken);
}
