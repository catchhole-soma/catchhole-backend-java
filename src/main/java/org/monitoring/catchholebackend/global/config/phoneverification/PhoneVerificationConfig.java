package org.monitoring.catchholebackend.global.config.phoneverification;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties({PhoneVerificationProperties.class, SmsProperties.class})
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
    public SmartInitializingSingleton phoneVerificationStartupValidator(
            Environment environment,
            PhoneVerificationProperties verificationProperties,
            SmsProperties smsProperties
    ) {
        return () -> {
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
