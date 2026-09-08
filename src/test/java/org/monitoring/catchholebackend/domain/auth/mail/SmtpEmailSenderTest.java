package org.monitoring.catchholebackend.domain.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.monitoring.catchholebackend.domain.auth.exception.AuthErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("SMTP 이메일 인증번호 발송기")
class SmtpEmailSenderTest {

    @Test
    @DisplayName("한 수신자에게 UTF-8 일반 텍스트 인증 메일을 한 번 발송한다")
    void sendsSinglePlainTextVerificationMessage() throws Exception {
        JavaMailSender mailSender = mockMailSender();
        SmtpEmailSender sender = new SmtpEmailSender(mailSender, "sender@example.com");

        sender.sendVerificationCode("recipient@example.com", "654321");

        ArgumentCaptor<MimeMessage> captured = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, times(1)).send(captured.capture());
        MimeMessage message = captured.getValue();
        message.saveChanges();
        assertThat(message.getFrom()).extracting(Object::toString).containsExactly("sender@example.com");
        assertThat(message.getRecipients(Message.RecipientType.TO))
                .extracting(Object::toString).containsExactly("recipient@example.com");
        assertThat(message.getRecipients(Message.RecipientType.CC)).isNull();
        assertThat(message.getRecipients(Message.RecipientType.BCC)).isNull();
        assertThat(message.getSubject()).isEqualTo("[캐치홀] 회원가입 이메일 인증번호");
        assertThat(message.getContentType()).contains("text/plain", "UTF-8");
        assertThat(message.getContent().toString())
                .contains("654321", "유효시간", "가장 최근", "직접 요청하지 않았다면");
        assertThat(sender.isFake()).isFalse();
    }

    @Test
    @DisplayName("SMTP 실패 원문과 원인을 제거하고 재시도 없이 가용성 오류로 변환한다")
    void sanitizesProviderFailureWithoutRetry(CapturedOutput output) {
        JavaMailSender mailSender = mockMailSender();
        String sensitiveDetails = "smtp-password recipient@example.com verification-654321";
        doThrow(new MailSendException(sensitiveDetails, new IllegalStateException(sensitiveDetails)))
                .when(mailSender).send(any(MimeMessage.class));
        SmtpEmailSender sender = new SmtpEmailSender(mailSender, "sender@example.com");

        assertThatThrownBy(() -> sender.sendVerificationCode("recipient@example.com", "654321"))
                .isInstanceOfSatisfying(AppException.class, exception -> {
                    assertThat(exception.getResultCode()).isEqualTo(AuthErrorCode.AUTH_EMAIL_VERIFICATION_UNAVAILABLE);
                    assertThat(exception.getCause()).isNull();
                    assertThat(exception.getMessage()).doesNotContain("smtp-password", "recipient@", "654321");
                });

        verify(mailSender, times(1)).send(any(MimeMessage.class));
        assertThat(output.getAll()).doesNotContain("smtp-password", "recipient@", "654321");
    }

    @Test
    @DisplayName("복수 수신자나 헤더 삽입 주소는 SMTP 발송 전에 거절한다")
    void rejectsRecipientHeaderInjection() {
        JavaMailSender mailSender = mockMailSender();
        SmtpEmailSender sender = new SmtpEmailSender(mailSender, "sender@example.com");

        for (String recipient : new String[]{
                "recipient@example.com,other@example.com",
                "recipient@example.com\r\nBcc: other@example.com",
                "group:recipient@example.com;"
        }) {
            assertThatThrownBy(() -> sender.sendVerificationCode(recipient, "654321"))
                    .isInstanceOf(AppException.class);
        }
        verify(mailSender, times(0)).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("Fake 발송기는 외부 호출 없이 고정코드 모드임을 알린다")
    void fakeSenderDoesNotSend() {
        FakeEmailSender sender = new FakeEmailSender();
        sender.sendVerificationCode("recipient@example.com", "123456");
        assertThat(sender.isFake()).isTrue();
    }

    private JavaMailSender mockMailSender() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
        return sender;
    }
}
