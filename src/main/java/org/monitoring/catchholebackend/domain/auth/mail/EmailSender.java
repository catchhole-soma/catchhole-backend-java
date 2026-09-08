package org.monitoring.catchholebackend.domain.auth.mail;

/** 이메일 인증번호 발송 포트. 수신 주소, 인증번호와 SMTP 자격 증명은 로그에 남기지 않는다. */
public interface EmailSender {

    /** 타임아웃 뒤 실제 접수 여부를 알 수 없으므로 자동 재시도하지 않는다. */
    void sendVerificationCode(String email, String code);

    default boolean isFake() {
        return false;
    }
}
