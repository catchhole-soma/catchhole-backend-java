package org.monitoring.catchholebackend.domain.auth.service;

import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.dto.response.PhoneVerificationConfirmResponse;
import org.monitoring.catchholebackend.domain.auth.dto.response.PhoneVerificationSendResponse;
import org.monitoring.catchholebackend.domain.auth.exception.AuthErrorCode;
import org.monitoring.catchholebackend.domain.auth.mapper.PhoneVerificationMapper;
import org.monitoring.catchholebackend.domain.auth.phone.PhoneVerificationCodeGenerator;
import org.monitoring.catchholebackend.domain.auth.phone.PhoneVerificationHasher;
import org.monitoring.catchholebackend.domain.auth.phone.PhoneVerificationRateLimiter;
import org.monitoring.catchholebackend.domain.auth.phone.PhoneVerificationStore;
import org.monitoring.catchholebackend.domain.auth.phone.PhoneVerificationTokenGenerator;
import org.monitoring.catchholebackend.domain.auth.sms.SmsSender;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PhoneVerificationServiceImpl implements PhoneVerificationService {

    private static final Logger log = LoggerFactory.getLogger(PhoneVerificationServiceImpl.class);

    // PhoneVerificationStore의 Lua 확인 결과 중 실패 상태를 API 오류로 변환하기 위한 계약값이다.
    private static final int CONFIRM_EXPIRED = 0;
    private static final int CONFIRM_INVALID = -1;
    private static final int CONFIRM_ATTEMPTS_EXCEEDED = -2;
    private static final int CONFIRM_UNAVAILABLE = -3;

    private final MemberRepository memberRepository;
    private final PhoneVerificationHasher phoneVerificationHasher;
    private final PhoneVerificationRateLimiter phoneVerificationRateLimiter;
    private final PhoneVerificationStore phoneVerificationStore;
    private final PhoneVerificationCodeGenerator phoneVerificationCodeGenerator;
    private final PhoneVerificationTokenGenerator phoneVerificationTokenGenerator;
    private final SmsSender smsSender;
    private final PhoneVerificationMapper phoneVerificationMapper;

    @Override
    public PhoneVerificationSendResponse sendPhoneVerificationCode(String phoneNumber, String clientIp) {
        if (memberRepository.existsByPhoneNumber(phoneNumber)) {
            throw new AppException(AuthErrorCode.AUTH_PHONE_NUMBER_DUPLICATED);
        }

        String phoneHash = phoneVerificationHasher.hashIdentifier(phoneNumber);
        String ipHash = phoneVerificationHasher.hashIdentifier(clientIp == null ? "unknown" : clientIp);
        phoneVerificationRateLimiter.acquireSendPermit(phoneHash, ipHash);

        String verificationId = phoneVerificationTokenGenerator.generate();
        String verificationCode = phoneVerificationCodeGenerator.generate();
        phoneVerificationStore.replaceActiveVerificationFlow(
                phoneHash,
                verificationId,
                phoneNumber,
                phoneVerificationHasher.hashVerificationCode(verificationId, verificationCode)
        );

        try {
            smsSender.sendVerificationCode(phoneNumber, verificationCode);
            log.info("휴대폰 인증 SMS 발송 요청이 접수되었습니다.");
        } catch (AppException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AppException(AuthErrorCode.AUTH_PHONE_VERIFICATION_UNAVAILABLE, exception);
        }

        return phoneVerificationMapper.toSendResponse(
                verificationId,
                phoneVerificationStore.codeExpiration(),
                phoneVerificationStore.resendInterval()
        );
    }

    @Override
    public PhoneVerificationConfirmResponse confirmPhoneVerificationCode(
            String verificationId,
            String verificationCode
    ) {
        String signupToken = phoneVerificationTokenGenerator.generate();
        PhoneVerificationStore.ConfirmationResult confirmationResult =
                phoneVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                        verificationId,
                        phoneVerificationHasher.hashVerificationCode(verificationId, verificationCode),
                        signupToken
                );
        if (confirmationResult.status() == CONFIRM_EXPIRED) {
            log.info("휴대폰 인증 확인 흐름이 만료되었습니다.");
            throw new AppException(AuthErrorCode.AUTH_PHONE_VERIFICATION_EXPIRED);
        }
        if (confirmationResult.status() == CONFIRM_INVALID) {
            log.info("휴대폰 인증번호가 일치하지 않습니다.");
            throw new AppException(AuthErrorCode.AUTH_PHONE_VERIFICATION_CODE_INVALID);
        }
        if (confirmationResult.status() == CONFIRM_ATTEMPTS_EXCEEDED) {
            log.warn("휴대폰 인증번호 입력 가능 횟수를 초과했습니다.");
            throw new AppException(AuthErrorCode.AUTH_PHONE_VERIFICATION_ATTEMPTS_EXCEEDED);
        }
        if (confirmationResult.status() == CONFIRM_UNAVAILABLE) {
            throw new AppException(AuthErrorCode.AUTH_PHONE_VERIFICATION_UNAVAILABLE);
        }
        log.info("휴대폰 인증이 완료되었습니다.");
        return phoneVerificationMapper.toConfirmResponse(confirmationResult);
    }

    @Override
    public String getVerifiedPhoneNumberBySignupToken(String signupToken) {
        String phoneNumber = phoneVerificationStore.findPhoneNumberBySignupToken(signupToken);
        if (phoneNumber == null) {
            throw new AppException(AuthErrorCode.AUTH_PHONE_VERIFICATION_TOKEN_INVALID);
        }
        return phoneNumber;
    }

    @Override
    public void consumeSignupToken(String signupToken, String expectedPhoneNumber) {
        if (!phoneVerificationStore.consumeSignupToken(signupToken, expectedPhoneNumber)) {
            throw new AppException(AuthErrorCode.AUTH_PHONE_VERIFICATION_TOKEN_INVALID);
        }
    }
}
