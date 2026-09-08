package org.monitoring.catchholebackend.global.config.phoneverification;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.monitoring.catchholebackend.domain.auth.sms.FakeSmsSender;
import org.monitoring.catchholebackend.domain.auth.sms.SmsSender;
import org.monitoring.catchholebackend.domain.auth.sms.SolapiSmsSender;
import org.monitoring.catchholebackend.global.config.auth.SignupVerificationProperties;
import org.monitoring.catchholebackend.global.config.auth.SignupVerificationMethod;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties({PhoneVerificationProperties.class, SmsProperties.class, SignupVerificationProperties.class})
public class PhoneVerificationConfig {

    private static final int MINIMUM_SECRET_BYTES = 32;

    @Bean
    public Clock phoneVerificationClock() {
        return Clock.systemUTC();
    }

    @Bean
    public HttpClient smsHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Bean
    public SmsSender smsSender(
            Environment environment,
            SignupVerificationProperties signupProperties,
            SmsProperties smsProperties,
            @Qualifier("smsHttpClient") HttpClient httpClient,
            @Qualifier("phoneVerificationClock") Clock clock
    ) {
        if (signupProperties.verificationMethod() != SignupVerificationMethod.PHONE
                || environment.matchesProfiles("test", "e2e")) {
            return new FakeSmsSender();
        }
        if (smsProperties.isFake()) {
            return new FakeSmsSender();
        }
        if ("solapi".equalsIgnoreCase(smsProperties.provider())) {
            return new SolapiSmsSender(httpClient, clock, smsProperties);
        }
        throw new IllegalStateException("SMS provider는 fake 또는 solapi여야 합니다.");
    }

    @Bean
    public SmartInitializingSingleton phoneVerificationStartupValidator(
            Environment environment,
            PhoneVerificationProperties verificationProperties,
            SmsProperties smsProperties,
            SignupVerificationProperties signupProperties
    ) {
        return () -> {
            if (signupProperties.verificationMethod() != SignupVerificationMethod.PHONE) {
                return;
            }
            String hashSecret = verificationProperties.hashSecret();
            if (!StringUtils.hasText(hashSecret)
                    || hashSecret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SECRET_BYTES) {
                throw new IllegalStateException(
                        "auth.phone-verification.hash-secret은 최소 32바이트 이상이어야 합니다."
                );
            }
            if (environment.matchesProfiles("prod") && smsProperties.isFake()) {
                throw new IllegalStateException("prod 프로파일에서는 fake SMS provider를 사용할 수 없습니다.");
            }
        };
    }
}
