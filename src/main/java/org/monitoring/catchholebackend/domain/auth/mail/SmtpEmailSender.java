package org.monitoring.catchholebackend.domain.auth.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.monitoring.catchholebackend.domain.auth.exception.AuthErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);
    private static final String SUBJECT = "[캐치홀] 회원가입 이메일 인증번호";
    private static final String BODY_TEMPLATE = """
            캐치홀 회원가입을 위한 이메일 인증번호입니다.

            인증번호: %s

            회원가입 화면에 표시된 유효시간 안에 입력해 주세요.
            인증번호를 다시 요청했다면 가장 최근에 받은 번호를 입력해 주세요.
            직접 요청하지 않았다면 이 메일을 무시하셔도 됩니다.
            """;

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void sendVerificationCode(String email, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setValidateAddresses(true);
            helper.setFrom(from);
            InternetAddress recipient = new InternetAddress(email, true);
            if (recipient.isGroup() || !email.equals(recipient.getAddress())
                    || email.contains("\r") || email.contains("\n")) {
                throw new MessagingException("수신 이메일 주소 형식이 올바르지 않습니다.");
            }
            helper.setTo(recipient);
            helper.setSubject(SUBJECT);
            helper.setText(BODY_TEMPLATE.formatted(code), false);
            mailSender.send(message);
        } catch (MailException | MessagingException | IllegalArgumentException exception) {
            // MailException의 원문/원인에는 수신 주소와 메일 본문이 포함될 수 있다.
            log.warn("SMTP 이메일 인증번호 발송에 실패했습니다.");
            throw new AppException(AuthErrorCode.AUTH_EMAIL_VERIFICATION_UNAVAILABLE);
        }
    }
}
