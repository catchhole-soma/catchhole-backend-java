package org.monitoring.catchholebackend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.monitoring.catchholebackend.domain.auth.dto.request.AuthLoginRequest;
import org.monitoring.catchholebackend.domain.auth.dto.request.AuthSignupRequest;
import org.monitoring.catchholebackend.domain.auth.entity.RefreshToken;
import org.monitoring.catchholebackend.domain.auth.exception.AuthErrorCode;
import org.monitoring.catchholebackend.domain.auth.repository.RefreshTokenRepository;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.auth.token.RefreshTokenGenerator;
import org.monitoring.catchholebackend.domain.auth.token.TokenHashProvider;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.mapper.MemberMapper;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.global.config.auth.AuthProperties;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RefreshTokenGenerator refreshTokenGenerator;

    @Mock
    private TokenHashProvider tokenHashProvider;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        AuthProperties authProperties = new AuthProperties(
                new AuthProperties.Jwt("test-secret-must-be-at-least-32-bytes", Duration.ofMinutes(30)),
                Duration.ofDays(14),
                new AuthProperties.Cookie(false, "Lax")
        );
        authService = new AuthServiceImpl(
                memberRepository,
                refreshTokenRepository,
                passwordEncoder,
                jwtTokenProvider,
                refreshTokenGenerator,
                tokenHashProvider,
                new MemberMapper(),
                authProperties
        );
    }

    @Test
    void signupCreatesMemberWithHashedPassword() {
        AuthSignupRequest request = new AuthSignupRequest(
                "writer@example.com",
                "password123",
                "01012345678",
                "작가"
        );
        Member savedMember = member("writer@example.com", "encoded-password", "01012345678", "작가");
        when(memberRepository.existsByEmail(request.email())).thenReturn(false);
        when(memberRepository.existsByPhoneNumber(request.phoneNumber())).thenReturn(false);
        when(passwordEncoder.encode(request.password())).thenReturn("encoded-password");
        when(memberRepository.save(any(Member.class))).thenReturn(savedMember);

        var response = authService.signup(request);

        assertThat(response.email()).isEqualTo("writer@example.com");
        assertThat(response.phoneVerified()).isFalse();
        ArgumentCaptor<Member> memberCaptor = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).save(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getPasswordHash()).isEqualTo("encoded-password");
    }

    @Test
    void signupRejectsDuplicatedEmail() {
        AuthSignupRequest request = new AuthSignupRequest(
                "writer@example.com",
                "password123",
                "01012345678",
                "작가"
        );
        when(memberRepository.existsByEmail(request.email())).thenReturn(true);

        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(AppException.class)
                .extracting("resultCode")
                .isEqualTo(AuthErrorCode.AUTH_EMAIL_DUPLICATED);

        verify(memberRepository, never()).save(any(Member.class));
    }

    @Test
    void loginIssuesAccessTokenAndStoresRefreshTokenHash() {
        Member member = member("writer@example.com", "encoded-password", "01012345678", "작가");
        when(memberRepository.findByEmail("writer@example.com")).thenReturn(Optional.of(member));
        when(passwordEncoder.matches("password123", "encoded-password")).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(member)).thenReturn("access-token");
        when(jwtTokenProvider.getAccessTokenExpiresInSeconds()).thenReturn(1800L);
        when(refreshTokenGenerator.generate()).thenReturn("refresh-token");
        when(tokenHashProvider.hash("refresh-token")).thenReturn("refresh-token-hash");

        AuthTokenIssueResult result = authService.login(new AuthLoginRequest("writer@example.com", "password123"));

        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        assertThat(result.tokenResponse().accessToken()).isEqualTo("access-token");
        assertThat(result.tokenResponse().expiresIn()).isEqualTo(1800L);
        ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(tokenCaptor.capture());
        assertThat(tokenCaptor.getValue().getTokenHash()).isEqualTo("refresh-token-hash");
        assertThat(tokenCaptor.getValue().getExpiresAt()).isAfter(LocalDateTime.now().plusDays(13));
    }

    @Test
    void refreshRevokesOldTokenAndIssuesNewToken() {
        Member member = member("writer@example.com", "encoded-password", "01012345678", "작가");
        RefreshToken oldToken = RefreshToken.builder()
                .member(member)
                .tokenHash("old-refresh-token-hash")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();
        when(tokenHashProvider.hash("old-refresh-token")).thenReturn("old-refresh-token-hash");
        when(refreshTokenRepository.findByTokenHash("old-refresh-token-hash")).thenReturn(Optional.of(oldToken));
        when(jwtTokenProvider.generateAccessToken(member)).thenReturn("new-access-token");
        when(jwtTokenProvider.getAccessTokenExpiresInSeconds()).thenReturn(1800L);
        when(refreshTokenGenerator.generate()).thenReturn("new-refresh-token");
        when(tokenHashProvider.hash("new-refresh-token")).thenReturn("new-refresh-token-hash");

        AuthTokenIssueResult result = authService.refresh("old-refresh-token");

        assertThat(oldToken.isRevoked()).isTrue();
        assertThat(result.refreshToken()).isEqualTo("new-refresh-token");
        assertThat(result.tokenResponse().accessToken()).isEqualTo("new-access-token");
    }

    @Test
    void logoutRevokesRefreshTokenWhenItExists() {
        RefreshToken refreshToken = RefreshToken.builder()
                .member(member("writer@example.com", "encoded-password", "01012345678", "작가"))
                .tokenHash("refresh-token-hash")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();
        when(tokenHashProvider.hash("refresh-token")).thenReturn("refresh-token-hash");
        when(refreshTokenRepository.findByTokenHash("refresh-token-hash")).thenReturn(Optional.of(refreshToken));

        authService.logout("refresh-token");

        assertThat(refreshToken.isRevoked()).isTrue();
    }

    private Member member(String email, String passwordHash, String phoneNumber, String displayName) {
        Member member = Member.register(email, passwordHash, phoneNumber, displayName);
        ReflectionTestUtils.setField(member, "id", 1L);
        return member;
    }
}
