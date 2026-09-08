package org.monitoring.catchholebackend.global.config.emaildelivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.auth.mail.EmailSender;
import org.monitoring.catchholebackend.domain.auth.mail.FakeEmailSender;
import org.monitoring.catchholebackend.domain.auth.mail.SmtpEmailSender;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@DisplayName("이메일 발송 설정과 운영 기동 검증")
class EmailDeliveryConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EmailDeliveryConfig.class)
            .withPropertyValues("auth.signup-verification.verification-method=EMAIL");

    @Test
    @DisplayName("로컬 기본값은 외부 메일을 발송하지 않는 Fake다")
    void defaultsToFakeLocally() {
        runner.withPropertyValues("spring.profiles.active=local").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(EmailSender.class);
            assertThat(context.getBean(EmailSender.class)).isInstanceOf(FakeEmailSender.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "e2e"})
    @DisplayName("test와 e2e는 SMTP provider 환경변수가 있어도 자격 증명 없이 Fake로 기동한다")
    void forcesFakeInTestProfiles(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile, "email.provider=smtp")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EmailSender.class)).isInstanceOf(FakeEmailSender.class);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"fake", "unknown", ""})
    @DisplayName("운영 이메일 가입에서 SMTP 이외 provider는 기동을 막는다")
    void rejectsNonSmtpForProductionEmail(String provider) {
        runner.withPropertyValues("spring.profiles.active=prod", "email.provider=" + provider)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"fake", "smtp"})
    @DisplayName("운영 전화번호 가입은 메일 자격 증명 없이 기동한다")
    void permitsProductionPhoneWithoutEmailCredentials(String provider) {
        runner.withPropertyValues("spring.profiles.active=prod", "email.provider=" + provider,
                        "auth.signup-verification.verification-method=PHONE")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EmailSender.class)).isInstanceOf(FakeEmailSender.class);
                });
    }

    @Test
    @DisplayName("운영 SMTP 설정 누락은 기동을 막는다")
    void rejectsMissingSmtpConfiguration() {
        runner.withPropertyValues("spring.profiles.active=prod", "email.provider=smtp")
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"host", "username", "password", "from"})
    @DisplayName("SMTP 필수 항목 하나가 비어도 기동을 막는다")
    void rejectsEachMissingSmtpSetting(String setting) {
        smtpRunner().withPropertyValues("email.smtp." + setting + "=")
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "email.smtp.port=25", "email.smtp.port=465", "email.smtp.host=https://smtp.example.com",
            "email.smtp.from=invalid", "email.smtp.from=one@example.com,two@example.com"
    })
    @DisplayName("STARTTLS 전용 포트와 유효한 호스트 및 발신 주소를 강제한다")
    void rejectsMalformedSmtpSettings(String setting) {
        smtpRunner().withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("유효한 SMTP 설정은 연결이나 발송 없이 발송기를 구성한다")
    void createsSmtpSenderWithoutConnecting() {
        smtpRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EmailSender.class)).isInstanceOf(SmtpEmailSender.class);
        });
    }

    @Test
    @DisplayName("SMTP 클라이언트는 STARTTLS 필수와 인증서 호스트 검증 및 유한 타임아웃을 사용한다")
    void enforcesTlsTimeoutAndLoggingConfiguration() {
        EmailDeliveryProperties.Smtp properties = new EmailDeliveryProperties.Smtp(
                "smtp.example.com", 587, "test-user", "test-password", "sender@example.com"
        );
        JavaMailSenderImpl sender = new EmailDeliveryConfig().createSmtpMailSender(properties);

        assertThat(sender.getProtocol()).isEqualTo("smtp");
        assertThat(sender.getHost()).isEqualTo("smtp.example.com");
        assertThat(sender.getPort()).isEqualTo(587);
        assertThat(sender.getDefaultEncoding()).isEqualTo("UTF-8");
        assertThat(sender.getJavaMailProperties()).containsAllEntriesOf(Map.ofEntries(
                Map.entry("mail.smtp.auth", "true"),
                Map.entry("mail.smtp.starttls.enable", "true"),
                Map.entry("mail.smtp.starttls.required", "true"),
                Map.entry("mail.smtp.ssl.checkserveridentity", "true"),
                Map.entry("mail.smtp.ssl.protocols", "TLSv1.3 TLSv1.2"),
                Map.entry("mail.smtp.connectiontimeout", "3000"),
                Map.entry("mail.smtp.timeout", "5000"),
                Map.entry("mail.smtp.writetimeout", "5000"),
                Map.entry("mail.debug", "false"),
                Map.entry("mail.debug.auth", "false")
        ));
        assertThat(sender.getJavaMailProperties()).doesNotContainKey("mail.smtp.ssl.trust");
        assertThat(sender.getSession().getDebug()).isFalse();
        assertThat(properties.toString()).doesNotContain("test-user", "test-password", "sender@");
    }

    private ApplicationContextRunner smtpRunner() {
        return runner.withPropertyValues("spring.profiles.active=prod", "email.provider=smtp",
                "email.smtp.host=smtp.example.com", "email.smtp.port=587", "email.smtp.username=test-user",
                "email.smtp.password=test-password", "email.smtp.from=sender@example.com");
    }
}
