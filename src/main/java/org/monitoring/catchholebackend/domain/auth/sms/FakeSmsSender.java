package org.monitoring.catchholebackend.domain.auth.sms;

public class FakeSmsSender implements SmsSender {

    @Override
    public void sendVerificationCode(String phoneNumber, String code) {
        // local/test 환경에서는 외부 발송 없이 고정 인증번호만 검증한다.
    }
}
