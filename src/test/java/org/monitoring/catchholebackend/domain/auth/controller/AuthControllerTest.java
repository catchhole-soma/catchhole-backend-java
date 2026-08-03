package org.monitoring.catchholebackend.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.dto.request.AuthLoginRequest;
import org.monitoring.catchholebackend.domain.auth.dto.request.AuthSignupRequest;
import org.monitoring.catchholebackend.domain.auth.dto.response.AuthTokenResponse;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.auth.service.AuthService;
import org.monitoring.catchholebackend.domain.auth.service.AuthTokenIssueResult;
import org.monitoring.catchholebackend.domain.auth.token.RefreshTokenCookieFactory;
import org.monitoring.catchholebackend.domain.member.type.MemberRole;
import org.monitoring.catchholebackend.domain.member.type.MemberStatus;
import org.monitoring.catchholebackend.global.config.auth.AuthProperties;
import org.springframework.http.HttpHeaders;

@DisplayName("인증 컨트롤러")
class AuthControllerTest {

    private final AuthService authService = org.mockito.Mockito.mock(AuthService.class);
    private final RefreshTokenCookieFactory cookieFactory = new RefreshTokenCookieFactory(
            new AuthProperties(
                    new AuthProperties.Jwt("test-secret-must-be-at-least-32-bytes", java.time.Duration.ofMinutes(30)),
                    java.time.Duration.ofDays(14),
                    new AuthProperties.Cookie(false, "Lax")
            )
    );
    private final AuthController authController = new AuthController(authService, cookieFactory);

    @Test
    @DisplayName("회원가입은 access token과 refresh token 쿠키를 반환한다")
    void signupReturnsAccessTokenAndRefreshTokenCookie() {
        AuthSignupRequest request = new AuthSignupRequest(
                "writer@example.com",
                "password123",
                "작가",
                "phone-verification-token"
        );
        AuthTokenResponse tokenResponse = AuthTokenResponse.bearer("access-token", 1800L);
        when(authService.signup(request))
                .thenReturn(new AuthTokenIssueResult(tokenResponse, "refresh-token"));

        var response = authController.signup(request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("회원가입이 완료되었습니다.");
        assertThat(response.getBody().data().accessToken()).isEqualTo("access-token");
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("refreshToken=refresh-token")
                .contains("HttpOnly")
                .contains("Path=/api/v1/auth")
                .contains("SameSite=Lax");
    }

    @Test
    @DisplayName("로그인은 access token과 refresh token 쿠키를 반환한다")
    void loginReturnsAccessTokenAndRefreshTokenCookie() {
        AuthTokenResponse tokenResponse = AuthTokenResponse.bearer("access-token", 1800L);
        when(authService.login(new AuthLoginRequest("writer@example.com", "password123")))
                .thenReturn(new AuthTokenIssueResult(tokenResponse, "refresh-token"));

        var response = authController.login(new AuthLoginRequest("writer@example.com", "password123"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().accessToken()).isEqualTo("access-token");
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("refreshToken=refresh-token")
                .contains("HttpOnly")
                .contains("Path=/api/v1/auth")
                .contains("SameSite=Lax");
    }

    @Test
    @DisplayName("로그아웃은 refresh token을 폐기하고 쿠키를 삭제한다")
    void logoutRevokesRefreshTokenAndDeletesCookie() {
        var response = authController.logout("refresh-token");

        verify(authService).logout("refresh-token");
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("refreshToken=")
                .contains("Max-Age=0")
                .contains("HttpOnly");
    }

    @Test
    @DisplayName("현재 사용자 조회는 인증 principal을 사용한다")
    void getMeUsesAuthenticationPrincipal() {
        var response = authController.getMe(memberPrincipal());

        assertThat(response.data().id()).isEqualTo(1L);
        assertThat(response.data().email()).isEqualTo("writer@example.com");
        verifyNoInteractions(authService);
    }

    private MemberPrincipal memberPrincipal() {
        return new MemberPrincipal(
                1L,
                "writer@example.com",
                "01012345678",
                false,
                "작가",
                null,
                MemberStatus.ACTIVE,
                MemberRole.AUTHOR
        );
    }
}
