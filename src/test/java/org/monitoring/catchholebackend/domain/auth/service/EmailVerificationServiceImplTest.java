package org.monitoring.catchholebackend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.monitoring.catchholebackend.domain.auth.email.EmailVerificationCodeGenerator;
import org.monitoring.catchholebackend.domain.auth.email.EmailVerificationHasher;
import org.monitoring.catchholebackend.domain.auth.email.EmailVerificationRateLimiter;
import org.monitoring.catchholebackend.domain.auth.email.EmailVerificationStore;
import org.monitoring.catchholebackend.domain.auth.email.EmailVerificationTokenGenerator;
import org.monitoring.catchholebackend.domain.auth.exception.AuthErrorCode;
import org.monitoring.catchholebackend.domain.auth.mail.EmailSender;
import org.monitoring.catchholebackend.domain.auth.mapper.EmailVerificationMapper;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.global.config.auth.SignupVerificationMethod;
import org.monitoring.catchholebackend.global.exception.AppException;

@ExtendWith(MockitoExtension.class)
@DisplayName("이메일 인증 서비스 단위 테스트")
class EmailVerificationServiceImplTest {

    @Mock private SignupVerificationPolicy signupVerificationPolicy;
    @Mock private MemberRepository memberRepository;
    @Mock private EmailVerificationHasher emailVerificationHasher;
    @Mock private EmailVerificationRateLimiter emailVerificationRateLimiter;
    @Mock private EmailVerificationStore emailVerificationStore;
    @Mock private EmailVerificationCodeGenerator emailVerificationCodeGenerator;
    @Mock private EmailVerificationTokenGenerator emailVerificationTokenGenerator;
    @Mock private EmailSender emailSender;
    @Mock private EmailVerificationMapper emailVerificationMapper;
    @InjectMocks private EmailVerificationServiceImpl emailVerificationService;

    @Test
    @DisplayName("Redis 발송 제한 확인이 실패하면 인증 흐름을 만들거나 메일을 보내지 않는다")
    void redisFailurePreventsMailSend() {
        mockIdentifiers();
        doThrow(new AppException(AuthErrorCode.AUTH_EMAIL_VERIFICATION_UNAVAILABLE))
                .when(emailVerificationRateLimiter).acquireSendPermit("email-hash", "ip-hash");

        assertError(() -> emailVerificationService.sendEmailVerificationCode("Writer@example.com", "127.0.0.1"),
                AuthErrorCode.AUTH_EMAIL_VERIFICATION_UNAVAILABLE);
        verifyNoInteractions(emailVerificationStore, emailSender);
    }

    @Test
    @DisplayName("비활성 인증 방식에서는 발송과 코드 확인 모두 외부 작업 전에 거부한다")
    void disabledMethodRejectsSendAndConfirm() {
        doThrow(new AppException(AuthErrorCode.AUTH_SIGNUP_VERIFICATION_METHOD_DISABLED))
                .when(signupVerificationPolicy).requireMethod(SignupVerificationMethod.EMAIL);

        assertError(() -> emailVerificationService.sendEmailVerificationCode("Writer@example.com", "127.0.0.1"),
                AuthErrorCode.AUTH_SIGNUP_VERIFICATION_METHOD_DISABLED);
        assertError(() -> emailVerificationService.confirmEmailVerificationCode("id", "123456"),
                AuthErrorCode.AUTH_SIGNUP_VERIFICATION_METHOD_DISABLED);
        verifyNoInteractions(memberRepository, emailVerificationStore, emailSender, emailVerificationRateLimiter);
    }

    @Test
    @DisplayName("기존 이메일은 인증 메일 발송 전에 중복으로 거부한다")
    void duplicateEmailPreventsMailSend() {
        when(memberRepository.existsByEmail("Writer@example.com")).thenReturn(true);
        assertError(() -> emailVerificationService.sendEmailVerificationCode(" Writer@example.com ", "127.0.0.1"),
                AuthErrorCode.AUTH_EMAIL_DUPLICATED);
        verifyNoInteractions(emailSender, emailVerificationRateLimiter, emailVerificationStore);
    }

    @Test
    @DisplayName("발송 이메일은 양끝 공백만 정리하고 대소문자를 유지한다")
    void normalizesEmailWithoutChangingCase() {
        mockIdentifiers();
        when(emailVerificationTokenGenerator.generate()).thenReturn("id");
        when(emailVerificationCodeGenerator.generate()).thenReturn("123456");
        when(emailVerificationHasher.hashVerificationCode("id", "123456")).thenReturn("code-hash");

        emailVerificationService.sendEmailVerificationCode("  Writer@example.com  ", "127.0.0.1");

        verify(memberRepository).existsByEmail("Writer@example.com");
        verify(emailVerificationStore).replaceActiveVerificationFlow(
                "email-hash", "id", "Writer@example.com", "code-hash");
        verify(emailSender).sendVerificationCode("Writer@example.com", "123456");
    }

    @Test
    @DisplayName("인증 식별자는 도메인만 소문자로 묶고 발송 및 토큰 저장 이메일 대소문자는 보존한다")
    void canonicalizesVerificationDomainWithoutChangingStoredEmail() {
        mockIdentifiers();
        when(emailVerificationTokenGenerator.generate()).thenReturn("id");
        when(emailVerificationCodeGenerator.generate()).thenReturn("123456");
        when(emailVerificationHasher.hashVerificationCode("id", "123456")).thenReturn("code-hash");

        emailVerificationService.sendEmailVerificationCode("  Writer@EXAMPLE.COM  ", "127.0.0.1");

        verify(memberRepository).existsByEmail("Writer@EXAMPLE.COM");
        verify(emailVerificationHasher).hashIdentifier("Writer@example.com");
        verify(emailVerificationRateLimiter).acquireSendPermit("email-hash", "ip-hash");
        verify(emailVerificationStore).replaceActiveVerificationFlow(
                "email-hash", "id", "Writer@EXAMPLE.COM", "code-hash");
        verify(emailSender).sendVerificationCode("Writer@EXAMPLE.COM", "123456");
    }

    @Test
    @DisplayName("메일 서비스 장애를 일시적인 인증 장애로 반환하고 자동 재시도하지 않는다")
    void smtpFailureReturnsUnavailable() {
        mockIdentifiers();
        doThrow(new IllegalStateException("메일 서비스 연결 실패"))
                .when(emailSender).sendVerificationCode(any(), any());

        assertError(() -> emailVerificationService.sendEmailVerificationCode("Writer@example.com", "127.0.0.1"),
                AuthErrorCode.AUTH_EMAIL_VERIFICATION_UNAVAILABLE);
        verify(emailSender).sendVerificationCode(any(), any());
    }

    @Test
    @DisplayName("유효한 가입 토큰이 없거나 이미 소비되었으면 거부한다")
    void rejectsMissingAndConsumedSignupTokens() {
        assertError(() -> emailVerificationService.getVerifiedEmailBySignupToken("missing"),
                AuthErrorCode.AUTH_EMAIL_VERIFICATION_TOKEN_INVALID);
        assertError(() -> emailVerificationService.consumeSignupToken("consumed", "Writer@example.com"),
                AuthErrorCode.AUTH_EMAIL_VERIFICATION_TOKEN_INVALID);
    }

    private void mockIdentifiers() {
        when(emailVerificationHasher.hashIdentifier("Writer@example.com")).thenReturn("email-hash");
        when(emailVerificationHasher.hashIdentifier("127.0.0.1")).thenReturn("ip-hash");
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, AuthErrorCode error) {
        assertThatThrownBy(callable).isInstanceOf(AppException.class).extracting("resultCode").isEqualTo(error);
    }
}
