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
    private final PhoneVerificationHasher hasher;
    private final PhoneVerificationRateLimiter rateLimiter;
    private final PhoneVerificationStore store;
    private final PhoneVerificationCodeGenerator codeGenerator;
    private final PhoneVerificationTokenGenerator tokenGenerator;
    private final SmsSender smsSender;
    private final PhoneVerificationMapper phoneVerificationMapper;

    @Override
    public PhoneVerificationSendResponse start(String phoneNumber, String clientIp) {
        if (memberRepository.existsByPhoneNumber(phoneNumber)) {
            throw new AppException(AuthErrorCode.AUTH_PHONE_NUMBER_DUPLICATED);
        }

        String phoneHash = hasher.identifier(phoneNumber);
        String ipHash = hasher.identifier(clientIp == null ? "unknown" : clientIp);
        rateLimiter.acquire(phoneHash, ipHash);

        String verificationId = tokenGenerator.generate();
        String code = codeGenerator.generate();
        store.start(phoneHash, verificationId, phoneNumber, hasher.code(verificationId, code));

        try {
            smsSender.sendVerificationCode(phoneNumber, code);
            log.info("휴대폰 인증 SMS 발송 요청이 접수되었습니다.");
        } catch (AppException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AppException(AuthErrorCode.AUTH_PHONE_VERIFICATION_UNAVAILABLE, exception);
        }

        return phoneVerificationMapper.toSendResponse(
                verificationId,
                store.codeExpiration(),
                store.resendInterval()
        );
    }

    @Override
    public PhoneVerificationConfirmResponse confirm(String verificationId, String code) {
        String signupToken = tokenGenerator.generate();
        PhoneVerificationStore.ConfirmationResult result = store.confirm(
                verificationId,
                hasher.code(verificationId, code),
                signupToken
        );
        if (result.status() == CONFIRM_EXPIRED) {
            log.info("휴대폰 인증 확인 흐름이 만료되었습니다.");
            throw new AppException(AuthErrorCode.AUTH_PHONE_VERIFICATION_EXPIRED);
        }
        if (result.status() == CONFIRM_INVALID) {
            log.info("휴대폰 인증번호가 일치하지 않습니다.");
            throw new AppException(AuthErrorCode.AUTH_PHONE_VERIFICATION_CODE_INVALID);
        }
        if (result.status() == CONFIRM_ATTEMPTS_EXCEEDED) {
            log.warn("휴대폰 인증번호 입력 가능 횟수를 초과했습니다.");
            throw new AppException(AuthErrorCode.AUTH_PHONE_VERIFICATION_ATTEMPTS_EXCEEDED);
        }
        if (result.status() == CONFIRM_UNAVAILABLE) {
            throw new AppException(AuthErrorCode.AUTH_PHONE_VERIFICATION_UNAVAILABLE);
        }
        log.info("휴대폰 인증이 완료되었습니다.");
        return phoneVerificationMapper.toConfirmResponse(result);
    }

    @Override
    public String getVerifiedPhoneNumber(String signupToken) {
        String phoneNumber = store.findPhoneNumber(signupToken);
        if (phoneNumber == null) {
            throw new AppException(AuthErrorCode.AUTH_PHONE_VERIFICATION_TOKEN_INVALID);
        }
        return phoneNumber;
    }

    @Override
    public void consumeSignupToken(String signupToken, String expectedPhoneNumber) {
        if (!store.consume(signupToken, expectedPhoneNumber)) {
            throw new AppException(AuthErrorCode.AUTH_PHONE_VERIFICATION_TOKEN_INVALID);
        }
    }
}
