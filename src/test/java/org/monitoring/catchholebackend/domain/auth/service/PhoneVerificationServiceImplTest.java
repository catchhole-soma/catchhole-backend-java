package org.monitoring.catchholebackend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("휴대폰 인증 서비스 단위 테스트")
class PhoneVerificationServiceImplTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private PhoneVerificationHasher hasher;

    @Mock
    private PhoneVerificationRateLimiter rateLimiter;

    @Mock
    private PhoneVerificationStore store;

    @Mock
    private PhoneVerificationCodeGenerator codeGenerator;

    @Mock
    private PhoneVerificationTokenGenerator tokenGenerator;

    @Mock
    private SmsSender smsSender;

    @Mock
    private PhoneVerificationMapper phoneVerificationMapper;

    @InjectMocks
    private PhoneVerificationServiceImpl service;

    @Test
    @DisplayName("Redis 발송 제한 확인이 실패하면 인증 흐름을 만들거나 SMS를 보내지 않는다")
    void redisFailurePreventsSmsSend() {
        String phoneNumber = "01012345678";
        when(memberRepository.existsByPhoneNumber(phoneNumber)).thenReturn(false);
        when(hasher.identifier(phoneNumber)).thenReturn("phone-hash");
        when(hasher.identifier("127.0.0.1")).thenReturn("ip-hash");
        doThrow(new AppException(AuthErrorCode.AUTH_PHONE_VERIFICATION_UNAVAILABLE))
                .when(rateLimiter)
                .acquire("phone-hash", "ip-hash");

        assertThatThrownBy(() -> service.start(phoneNumber, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .extracting("resultCode")
                .isEqualTo(AuthErrorCode.AUTH_PHONE_VERIFICATION_UNAVAILABLE);

        verifyNoInteractions(store, smsSender);
    }
}
