package org.monitoring.catchholebackend.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.dto.request.AuthLoginRequest;
import org.monitoring.catchholebackend.domain.auth.dto.response.AuthTokenResponse;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.auth.service.AuthService;
import org.monitoring.catchholebackend.domain.auth.service.AuthTokenIssueResult;
import org.monitoring.catchholebackend.domain.auth.token.RefreshTokenCookieFactory;
import org.monitoring.catchholebackend.domain.member.entity.MemberRole;
import org.monitoring.catchholebackend.domain.member.entity.MemberStatus;
import org.monitoring.catchholebackend.global.config.auth.AuthProperties;
import org.springframework.http.HttpHeaders;

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
    void logoutRevokesRefreshTokenAndDeletesCookie() {
        var response = authController.logout("refresh-token");

        verify(authService).logout("refresh-token");
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("refreshToken=")
                .contains("Max-Age=0")
                .contains("HttpOnly");
    }

    @Test
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
