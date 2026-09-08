package org.monitoring.catchholebackend.domain.auth.mail;

public class FakeEmailSender implements EmailSender {

    @Override
    public void sendVerificationCode(String email, String code) {
        // local/test/e2e에서는 외부 발송 없이 고정 인증번호를 검증한다.
    }

    @Override
    public boolean isFake() {
        return true;
    }
}
