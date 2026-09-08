package org.monitoring.catchholebackend.domain.auth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.dto.response.EmailVerificationConfirmResponse;
import org.monitoring.catchholebackend.domain.auth.dto.response.EmailVerificationSendResponse;
import org.monitoring.catchholebackend.domain.auth.email.EmailVerificationStore;

@DisplayName("이메일 인증 Mapper 단위 테스트")
class EmailVerificationMapperTest {

    private final EmailVerificationMapper emailVerificationMapper = new EmailVerificationMapper();

    @Test
    @DisplayName("인증 흐름 식별자와 정책 시간을 발송 응답으로 변환한다")
    void toSendResponseMapsVerificationFlow() {
        EmailVerificationSendResponse response = emailVerificationMapper.toSendResponse(
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
        EmailVerificationStore.ConfirmationResult result =
                new EmailVerificationStore.ConfirmationResult(1, "signup-token", 600);

        EmailVerificationConfirmResponse response = emailVerificationMapper.toConfirmResponse(result);

        assertThat(response.emailVerificationToken()).isEqualTo("signup-token");
        assertThat(response.expiresInSeconds()).isEqualTo(600);
    }
}
