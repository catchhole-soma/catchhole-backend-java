package org.monitoring.catchholebackend.global.config.emailverification;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.service.SignupVerificationPolicy;
import org.monitoring.catchholebackend.global.config.auth.SignupVerificationMethod;
import org.monitoring.catchholebackend.global.config.auth.SignupVerificationProperties;

@DisplayName("이메일 인증 시작 설정 검증")
class EmailVerificationConfigTest {

    private final EmailVerificationConfig config = new EmailVerificationConfig();

    @Test
    @DisplayName("이메일 인증을 사용할 때 짧거나 없는 HMAC secret을 거부한다")
    void rejectsMissingSecretForEmailMode() {
        assertThatThrownBy(() -> validate(properties(null, 5), SignupVerificationMethod.EMAIL))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validate(properties("short", 5), SignupVerificationMethod.EMAIL))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("휴대폰 인증으로 전환하면 사용하지 않는 이메일 secret은 요구하지 않는다")
    void allowsMissingSecretForPhoneMode() {
        assertThatCode(() -> validate(properties(null, 5), SignupVerificationMethod.PHONE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("유효한 secret을 쓰더라도 오입력 제한을 0으로 설정할 수 없다")
    void rejectsInvalidAttemptLimit() {
        assertThatThrownBy(() -> validate(properties("test-email-verification-secret-at-least-32-bytes", 0),
                SignupVerificationMethod.EMAIL)).isInstanceOf(IllegalStateException.class);
    }

    private void validate(EmailVerificationProperties properties, SignupVerificationMethod method) {
        SignupVerificationPolicy policy = new SignupVerificationPolicy(new SignupVerificationProperties(method));
        config.emailVerificationStartupValidator(properties, policy).afterSingletonsInstantiated();
    }

    private EmailVerificationProperties properties(String secret, int attempts) {
        return new EmailVerificationProperties(secret, Duration.ofMinutes(5), Duration.ofSeconds(60),
                Duration.ofMinutes(10), attempts, 5, 10, 10, 20, 100, 3000);
    }
}
