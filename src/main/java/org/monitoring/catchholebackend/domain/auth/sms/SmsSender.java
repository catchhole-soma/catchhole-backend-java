package org.monitoring.catchholebackend.domain.auth.sms;

public interface SmsSender {

    void sendVerificationCode(String phoneNumber, String code);
}
