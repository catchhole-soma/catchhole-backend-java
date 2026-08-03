package org.monitoring.catchholebackend.domain.auth.phone;

import java.security.SecureRandom;
import org.monitoring.catchholebackend.global.config.phoneverification.SmsProperties;
import org.springframework.stereotype.Component;

@Component
public class PhoneVerificationCodeGenerator {

    public static final String FAKE_CODE = "123456";
    private static final int CODE_BOUND = 1_000_000;

    private final SecureRandom secureRandom = new SecureRandom();
    private final SmsProperties smsProperties;

    public PhoneVerificationCodeGenerator(SmsProperties smsProperties) {
        this.smsProperties = smsProperties;
    }

    public String generate() {
        if (smsProperties.isFake()) {
            return FAKE_CODE;
        }
        return "%06d".formatted(secureRandom.nextInt(CODE_BOUND));
    }
}
