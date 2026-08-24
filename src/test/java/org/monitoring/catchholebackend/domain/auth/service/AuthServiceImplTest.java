package org.monitoring.catchholebackend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.monitoring.catchholebackend.domain.auth.dto.request.AuthLoginRequest;
import org.monitoring.catchholebackend.domain.auth.dto.request.AuthSignupRequest;
import org.monitoring.catchholebackend.domain.auth.entity.RefreshToken;
import org.monitoring.catchholebackend.domain.auth.exception.AuthErrorCode;
import org.monitoring.catchholebackend.domain.auth.mapper.AuthMapper;
import org.monitoring.catchholebackend.domain.auth.repository.RefreshTokenRepository;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.auth.token.RefreshTokenGenerator;
import org.monitoring.catchholebackend.domain.auth.token.TokenHashProvider;
import org.monitoring.catchholebackend.domain.legal.entity.LegalDocument;
import org.monitoring.catchholebackend.domain.legal.service.LegalDocumentService;
import org.monitoring.catchholebackend.domain.legal.service.SignupLegalDocuments;
import org.monitoring.catchholebackend.domain.legal.type.LegalDocumentType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.entity.MemberLegalRecord;
import org.monitoring.catchholebackend.domain.member.repository.MemberLegalRecordRepository;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.member.type.LegalRecordAction;
import org.monitoring.catchholebackend.global.config.auth.AuthProperties;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("인증 서비스")
class AuthServiceImplTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private MemberLegalRecordRepository memberLegalRecordRepository;

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

    @Mock
    private PhoneVerificationService phoneVerificationService;

    @Mock
    private LegalDocumentService legalDocumentService;

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
                memberLegalRecordRepository,
                refreshTokenRepository,
                passwordEncoder,
                jwtTokenProvider,
                refreshTokenGenerator,
                tokenHashProvider,
                authProperties,
                phoneVerificationService,
                new AuthMapper(),
                legalDocumentService
        );
    }

    @Test
    @DisplayName("회원가입은 비밀번호를 암호화해 회원을 저장하고 인증 토큰을 발급한다")
    void signupCreatesMemberAndIssuesTokens() {
        AuthSignupRequest request = new AuthSignupRequest(
                "writer@example.com",
                "password123",
                "작가",
                true,
                true,
                true,
                3L,
                4L,
                "phone-verification-token"
        );
        Member savedMember = verifiedMember("writer@example.com", "encoded-password", "01012345678", "작가");
        SignupLegalDocuments legalDocuments = signupLegalDocuments();
        when(phoneVerificationService.getVerifiedPhoneNumberBySignupToken(request.phoneVerificationToken()))
                .thenReturn("01012345678");
        when(memberRepository.existsByEmail(request.email())).thenReturn(false);
        when(memberRepository.existsByPhoneNumber("01012345678")).thenReturn(false);
        when(passwordEncoder.encode(request.password())).thenReturn("encoded-password");
        when(legalDocumentService.requireCurrentSignupDocuments(3L, 4L)).thenReturn(legalDocuments);
        when(memberRepository.save(any(Member.class))).thenReturn(savedMember);
        when(jwtTokenProvider.generateAccessToken(savedMember)).thenReturn("access-token");
        when(jwtTokenProvider.getAccessTokenExpiresInSeconds()).thenReturn(1800L);
        when(refreshTokenGenerator.generate()).thenReturn("refresh-token");
        when(tokenHashProvider.hash("refresh-token")).thenReturn("refresh-token-hash");

        AuthTokenIssueResult result = authService.signup(request);

        assertThat(result.tokenResponse().accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        ArgumentCaptor<Member> memberCaptor = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).save(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getPasswordHash()).isEqualTo("encoded-password");
        assertThat(memberCaptor.getValue().isPhoneVerified()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<MemberLegalRecord>> legalRecordCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(memberLegalRecordRepository).saveAll(legalRecordCaptor.capture());
        List<MemberLegalRecord> legalRecords = new java.util.ArrayList<>();
        legalRecordCaptor.getValue().forEach(legalRecords::add);
        assertThat(legalRecords)
                .extracting(
                        MemberLegalRecord::getDocumentType,
                        MemberLegalRecord::getActionType,
                        MemberLegalRecord::getDocumentVersion
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                LegalDocumentType.TERMS_OF_SERVICE,
                                LegalRecordAction.AGREED,
                                "2026-08-24"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                LegalDocumentType.PRIVACY_POLICY,
                                LegalRecordAction.ACKNOWLEDGED,
                                "2026-08-24"
                        )
                );
        assertThat(legalRecords)
                .allSatisfy(record -> assertThat(record.getMember()).isEqualTo(savedMember));
        ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(tokenCaptor.capture());
        assertThat(tokenCaptor.getValue().getMember()).isEqualTo(savedMember);
        assertThat(tokenCaptor.getValue().getTokenHash()).isEqualTo("refresh-token-hash");
        verify(phoneVerificationService).consumeSignupToken("phone-verification-token", "01012345678");
    }

    @Test
    @DisplayName("회원가입은 중복 이메일을 거부한다")
    void signupRejectsDuplicatedEmail() {
        AuthSignupRequest request = new AuthSignupRequest(
                "writer@example.com",
                "password123",
                "작가",
                true,
                true,
                true,
                3L,
                4L,
                "phone-verification-token"
        );
        when(phoneVerificationService.getVerifiedPhoneNumberBySignupToken(request.phoneVerificationToken()))
                .thenReturn("01012345678");
        when(memberRepository.existsByEmail(request.email())).thenReturn(true);

        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(AppException.class)
                .extracting("resultCode")
                .isEqualTo(AuthErrorCode.AUTH_EMAIL_DUPLICATED);

        verify(memberRepository, never()).save(any(Member.class));
    }

    @Test
    @DisplayName("로그인은 access token을 발급하고 refresh token 해시를 저장한다")
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
    @DisplayName("토큰 재발급은 기존 refresh token을 폐기하고 새 토큰을 발급한다")
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
    @DisplayName("로그아웃은 저장된 refresh token을 폐기한다")
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

    private Member verifiedMember(String email, String passwordHash, String phoneNumber, String displayName) {
        Member member = Member.registerPhoneVerified(
                email,
                passwordHash,
                phoneNumber,
                displayName,
                LocalDateTime.of(2026, 8, 24, 17, 0)
        );
        ReflectionTestUtils.setField(member, "id", 1L);
        return member;
    }

    private SignupLegalDocuments signupLegalDocuments() {
        return new SignupLegalDocuments(
                legalDocument(3L, LegalDocumentType.TERMS_OF_SERVICE),
                legalDocument(4L, LegalDocumentType.PRIVACY_POLICY)
        );
    }

    private LegalDocument legalDocument(Long id, LegalDocumentType type) {
        LegalDocument document = LegalDocument.published(
                type,
                "ko-KR",
                "2026-08-24",
                type == LegalDocumentType.TERMS_OF_SERVICE ? "CatchHole 이용약관" : "CatchHole 개인정보처리방침",
                "# 원문",
                "a".repeat(64),
                LocalDate.of(2026, 8, 24),
                LocalDateTime.of(2026, 8, 24, 18, 0)
        );
        ReflectionTestUtils.setField(document, "id", id);
        return document;
    }
}
