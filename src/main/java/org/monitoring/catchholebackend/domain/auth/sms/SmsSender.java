package org.monitoring.catchholebackend.domain.auth.sms;

/**
 * 휴대폰 인증번호 SMS 전송 포트다.
 * 구현체는 타임아웃 뒤 실제 발송 여부를 알 수 없으므로 자동 재시도하지 않고, 번호와 인증번호를 로그에 남기지 않는다.
 */
public interface SmsSender {

    /** 외부 SMS 제공자에 인증 메시지 발송을 한 번만 시도한다. */
    void sendVerificationCode(String phoneNumber, String code);
}
