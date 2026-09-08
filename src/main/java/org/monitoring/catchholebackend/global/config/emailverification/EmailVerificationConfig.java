package org.monitoring.catchholebackend.global.config.emailverification;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.monitoring.catchholebackend.domain.auth.service.SignupVerificationPolicy;
import org.monitoring.catchholebackend.global.config.auth.SignupVerificationMethod;

@Configuration
@EnableConfigurationProperties(EmailVerificationProperties.class)
public class EmailVerificationConfig {

    @Bean
    public Clock emailVerificationClock() {
        return Clock.systemUTC();
    }

    @Bean
    public SmartInitializingSingleton emailVerificationStartupValidator(
            EmailVerificationProperties properties,
            SignupVerificationPolicy signupVerificationPolicy
    ) {
        return () -> {
            if (signupVerificationPolicy.verificationMethod() == SignupVerificationMethod.EMAIL
                    && (!StringUtils.hasText(properties.hashSecret())
                    || properties.hashSecret().getBytes(StandardCharsets.UTF_8).length < 32)) {
                throw new IllegalStateException("auth.email-verification.hash-secret은 최소 32바이트 이상이어야 합니다.");
            }
            requirePositiveDuration(properties.codeExpiration(), "code-expiration");
            requirePositiveDuration(properties.resendInterval(), "resend-interval");
            requirePositiveDuration(properties.signupTokenExpiration(), "signup-token-expiration");
            if (properties.maxAttempts() < 1 || properties.emailHourlyLimit() < 1
                    || properties.emailDailyLimit() < 1 || properties.ipHourlyLimit() < 1
                    || properties.ipDailyLimit() < 1 || properties.globalDailyLimit() < 1
                    || properties.globalMonthlyLimit() < 1) {
                throw new IllegalStateException("이메일 인증 입력 횟수와 발송 제한은 1 이상이어야 합니다.");
            }
        };
    }

    private void requirePositiveDuration(Duration duration, String name) {
        if (duration == null || duration.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalStateException("auth.email-verification." + name + "은 1초 이상이어야 합니다.");
        }
    }
}
