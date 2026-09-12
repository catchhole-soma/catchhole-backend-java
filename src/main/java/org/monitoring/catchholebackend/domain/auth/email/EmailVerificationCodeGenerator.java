package org.monitoring.catchholebackend.domain.auth.email;

import java.security.SecureRandom;
import org.monitoring.catchholebackend.domain.auth.mail.EmailSender;
import org.springframework.stereotype.Component;

@Component
public class EmailVerificationCodeGenerator {

    public static final String FAKE_CODE = "123456";
    private static final int CODE_BOUND = 1_000_000;

    private final SecureRandom secureRandom = new SecureRandom();
    private final EmailSender emailSender;

    public EmailVerificationCodeGenerator(EmailSender emailSender) {
        this.emailSender = emailSender;
    }

    public String generate() {
        return emailSender.isFake() ? FAKE_CODE : "%06d".formatted(secureRandom.nextInt(CODE_BOUND));
    }
}
