package org.monitoring.catchholebackend.domain.auth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.dto.response.PhoneVerificationConfirmResponse;
import org.monitoring.catchholebackend.domain.auth.dto.response.PhoneVerificationSendResponse;
import org.monitoring.catchholebackend.domain.auth.phone.PhoneVerificationStore;

@DisplayName("휴대폰 인증 Mapper 단위 테스트")
class PhoneVerificationMapperTest {

    private final PhoneVerificationMapper phoneVerificationMapper = new PhoneVerificationMapper();

    @Test
    @DisplayName("인증 흐름 식별자와 정책 시간을 발송 응답으로 변환한다")
    void toSendResponseMapsVerificationFlow() {
        PhoneVerificationSendResponse response = phoneVerificationMapper.toSendResponse(
                "verification-id",
                Duration.ofMinutes(5),
                Duration.ofMinutes(1)
        );

        assertThat(response.verificationId()).isEqualTo("verification-id");
        assertThat(response.expiresInSeconds()).isEqualTo(300);
        assertThat(response.resendAfterSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("Redis 확인 결과를 회원가입 토큰 응답으로 변환한다")
    void toConfirmResponseMapsSignupToken() {
        PhoneVerificationStore.ConfirmationResult result =
                new PhoneVerificationStore.ConfirmationResult(1, "signup-token", 600);

        PhoneVerificationConfirmResponse response = phoneVerificationMapper.toConfirmResponse(result);

        assertThat(response.phoneVerificationToken()).isEqualTo("signup-token");
        assertThat(response.expiresInSeconds()).isEqualTo(600);
    }
}
