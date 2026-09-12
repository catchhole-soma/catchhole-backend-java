package org.monitoring.catchholebackend.global.config.emaildelivery;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.monitoring.catchholebackend.domain.auth.mail.EmailSender;
import org.monitoring.catchholebackend.domain.auth.mail.FakeEmailSender;
import org.monitoring.catchholebackend.domain.auth.mail.SmtpEmailSender;
import org.monitoring.catchholebackend.global.config.auth.SignupVerificationMethod;
import org.monitoring.catchholebackend.global.config.auth.SignupVerificationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties({EmailDeliveryProperties.class, SignupVerificationProperties.class})
public class EmailDeliveryConfig {

    @Bean
    public EmailSender emailSender(
            Environment environment,
            EmailDeliveryProperties properties,
            SignupVerificationProperties signupProperties
    ) {
        if (environment.matchesProfiles("test", "e2e")) {
            return new FakeEmailSender();
        }
        if (environment.matchesProfiles("prod") && signupProperties.verificationMethod() == SignupVerificationMethod.PHONE) {
            return new FakeEmailSender();
        }

        String provider = properties.provider();
        if ("smtp".equalsIgnoreCase(provider)) {
            JavaMailSenderImpl mailSender = createSmtpMailSender(properties.smtp());
            return new SmtpEmailSender(mailSender, properties.smtp().from());
        }
        if (environment.matchesProfiles("prod")) {
            throw new IllegalStateException("prod 이메일 가입은 email.provider=smtp 설정이 필요합니다.");
        }
        if (!StringUtils.hasText(provider) || "fake".equalsIgnoreCase(provider)) {
            return new FakeEmailSender();
        }
        throw new IllegalStateException("지원하지 않는 email.provider 설정입니다.");
    }

    JavaMailSenderImpl createSmtpMailSender(EmailDeliveryProperties.Smtp smtp) {
        validateSmtpProperties(smtp);
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setProtocol("smtp");
        sender.setHost(smtp.host());
        sender.setPort(smtp.port());
        sender.setUsername(smtp.username());
        sender.setPassword(smtp.password());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties mailProperties = new Properties();
        mailProperties.setProperty("mail.smtp.auth", "true");
        mailProperties.setProperty("mail.smtp.starttls.enable", "true");
        mailProperties.setProperty("mail.smtp.starttls.required", "true");
        mailProperties.setProperty("mail.smtp.ssl.enable", "false");
        mailProperties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        mailProperties.setProperty("mail.smtp.ssl.protocols", "TLSv1.3 TLSv1.2");
        mailProperties.setProperty("mail.smtp.connectiontimeout", "3000");
        mailProperties.setProperty("mail.smtp.timeout", "5000");
        mailProperties.setProperty("mail.smtp.writetimeout", "5000");
        mailProperties.setProperty("mail.smtp.quitwait", "false");
        mailProperties.setProperty("mail.smtp.sendpartial", "false");
        mailProperties.setProperty("mail.debug", "false");
        mailProperties.setProperty("mail.debug.auth", "false");
        sender.setJavaMailProperties(mailProperties);
        return sender;
    }

    private void validateSmtpProperties(EmailDeliveryProperties.Smtp smtp) {
        if (smtp == null
                || !StringUtils.hasText(smtp.host())
                || !StringUtils.hasText(smtp.username())
                || !StringUtils.hasText(smtp.password())
                || !StringUtils.hasText(smtp.from())) {
            throw new IllegalStateException("SMTP host, username, password, from 설정이 모두 필요합니다.");
        }
        if (smtp.port() != 587) {
            throw new IllegalStateException("SMTP 발송 포트는 STARTTLS를 사용하는 587이어야 합니다.");
        }
        if (smtp.host().length() > 253
                || !smtp.host().matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*")) {
            throw new IllegalStateException("SMTP host는 유효한 호스트 이름이어야 합니다.");
        }
        try {
            InternetAddress address = new InternetAddress(smtp.from(), true);
            address.validate();
            if (address.isGroup() || !smtp.from().equals(address.getAddress())
                    || smtp.from().contains("\r") || smtp.from().contains("\n")) {
                throw new AddressException();
            }
        } catch (AddressException exception) {
            throw new IllegalStateException("SMTP from은 단일 이메일 주소여야 합니다.");
        }
    }
}
