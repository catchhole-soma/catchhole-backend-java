package org.monitoring.catchholebackend.domain.auth.service;

import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.dto.response.EmailVerificationConfirmResponse;
import org.monitoring.catchholebackend.domain.auth.dto.response.EmailVerificationSendResponse;
import org.monitoring.catchholebackend.domain.auth.exception.AuthErrorCode;
import org.monitoring.catchholebackend.domain.auth.mapper.EmailVerificationMapper;
import org.monitoring.catchholebackend.domain.auth.email.EmailVerificationCodeGenerator;
import org.monitoring.catchholebackend.domain.auth.email.EmailAddressNormalizer;
import org.monitoring.catchholebackend.global.config.auth.SignupVerificationMethod;
import org.monitoring.catchholebackend.domain.auth.email.EmailVerificationHasher;
import org.monitoring.catchholebackend.domain.auth.email.EmailVerificationRateLimiter;
import org.monitoring.catchholebackend.domain.auth.email.EmailVerificationStore;
import org.monitoring.catchholebackend.domain.auth.email.EmailVerificationTokenGenerator;
import org.monitoring.catchholebackend.domain.auth.mail.EmailSender;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailVerificationServiceImpl implements EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationServiceImpl.class);

    // EmailVerificationStore의 Lua 확인 결과 중 실패 상태를 API 오류로 변환하기 위한 계약값이다.
    private static final int CONFIRM_EXPIRED = 0;
    private static final int CONFIRM_INVALID = -1;
    private static final int CONFIRM_ATTEMPTS_EXCEEDED = -2;
    private static final int CONFIRM_UNAVAILABLE = -3;

    private final SignupVerificationPolicy signupVerificationPolicy;
    private final MemberRepository memberRepository;
    private final EmailVerificationHasher emailVerificationHasher;
    private final EmailVerificationRateLimiter emailVerificationRateLimiter;
    private final EmailVerificationStore emailVerificationStore;
    private final EmailVerificationCodeGenerator emailVerificationCodeGenerator;
    private final EmailVerificationTokenGenerator emailVerificationTokenGenerator;
    private final EmailSender emailSender;
    private final EmailVerificationMapper emailVerificationMapper;

    @Override
    public EmailVerificationSendResponse sendEmailVerificationCode(String email, String clientIp) {
        signupVerificationPolicy.requireMethod(SignupVerificationMethod.EMAIL);
        email = EmailAddressNormalizer.normalize(email);
        if (memberRepository.existsByEmail(email)) {
            throw new AppException(AuthErrorCode.AUTH_EMAIL_DUPLICATED);
        }

        String emailHash = emailVerificationHasher.hashIdentifier(EmailAddressNormalizer.normalizeForVerificationKey(email));
        String ipHash = emailVerificationHasher.hashIdentifier(clientIp == null ? "unknown" : clientIp);
        emailVerificationRateLimiter.acquireSendPermit(emailHash, ipHash);

        String verificationId = emailVerificationTokenGenerator.generate();
        String verificationCode = emailVerificationCodeGenerator.generate();
        emailVerificationStore.replaceActiveVerificationFlow(
                emailHash,
                verificationId,
                email,
                emailVerificationHasher.hashVerificationCode(verificationId, verificationCode)
        );

        try {
            emailSender.sendVerificationCode(email, verificationCode);
            log.info("이메일 인증번호 발송 요청이 접수되었습니다.");
        } catch (AppException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AppException(AuthErrorCode.AUTH_EMAIL_VERIFICATION_UNAVAILABLE, exception);
        }

        return emailVerificationMapper.toSendResponse(
                verificationId,
                emailVerificationStore.codeExpiration(),
                emailVerificationStore.resendInterval()
        );
    }

    @Override
    public EmailVerificationConfirmResponse confirmEmailVerificationCode(
            String verificationId,
            String verificationCode
    ) {
        signupVerificationPolicy.requireMethod(SignupVerificationMethod.EMAIL);
        String signupToken = emailVerificationTokenGenerator.generate();
        EmailVerificationStore.ConfirmationResult confirmationResult =
                emailVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                        verificationId,
                        emailVerificationHasher.hashVerificationCode(verificationId, verificationCode),
                        signupToken
                );
        if (confirmationResult.status() == CONFIRM_EXPIRED) {
            log.info("이메일 인증 확인 흐름이 만료되었습니다.");
            throw new AppException(AuthErrorCode.AUTH_EMAIL_VERIFICATION_EXPIRED);
        }
        if (confirmationResult.status() == CONFIRM_INVALID) {
            log.info("이메일 인증번호가 일치하지 않습니다.");
            throw new AppException(AuthErrorCode.AUTH_EMAIL_VERIFICATION_CODE_INVALID);
        }
        if (confirmationResult.status() == CONFIRM_ATTEMPTS_EXCEEDED) {
            log.warn("이메일 인증번호 입력 가능 횟수를 초과했습니다.");
            throw new AppException(AuthErrorCode.AUTH_EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED);
        }
        if (confirmationResult.status() == CONFIRM_UNAVAILABLE) {
            throw new AppException(AuthErrorCode.AUTH_EMAIL_VERIFICATION_UNAVAILABLE);
        }
        log.info("이메일 인증이 완료되었습니다.");
        return emailVerificationMapper.toConfirmResponse(confirmationResult);
    }

    @Override
    public String getVerifiedEmailBySignupToken(String signupToken) {
        String email = emailVerificationStore.findEmailBySignupToken(signupToken);
        if (email == null) {
            throw new AppException(AuthErrorCode.AUTH_EMAIL_VERIFICATION_TOKEN_INVALID);
        }
        return email;
    }

    @Override
    public void consumeSignupToken(String signupToken, String expectedEmail) {
        if (!emailVerificationStore.consumeSignupToken(signupToken, expectedEmail)) {
            throw new AppException(AuthErrorCode.AUTH_EMAIL_VERIFICATION_TOKEN_INVALID);
        }
    }
}
