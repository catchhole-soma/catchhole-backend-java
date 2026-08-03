package org.monitoring.catchholebackend.domain.auth.service;

import org.monitoring.catchholebackend.domain.auth.dto.response.PhoneVerificationConfirmResponse;
import org.monitoring.catchholebackend.domain.auth.dto.response.PhoneVerificationSendResponse;

/**
 * 휴대폰 번호 소유 확인 흐름과 회원가입용 일회성 토큰의 수명주기를 관리한다.
 * 구현체는 인증번호·전화번호·클라이언트 IP 원문을 로그나 Redis key에 노출하지 않아야 한다.
 */
public interface PhoneVerificationService {

    /**
     * 가입되지 않은 번호인지 확인하고 전화번호·IP·전체 발송 제한을 통과하면 새 인증 흐름을 시작한다.
     * 같은 번호로 다시 요청하면 이전 흐름을 폐기하며, SMS 발송은 비용 중복을 막기 위해 한 번만 시도한다.
     */
    PhoneVerificationSendResponse start(String phoneNumber, String clientIp);

    /**
     * 인증번호를 원자적으로 검증하고 성공 시 회원가입용 토큰을 발급한다.
     * 같은 인증 흐름을 다시 확인하면 토큰이 유효한 동안 기존 토큰을 반환해 여러 토큰이 생기지 않게 한다.
     */
    PhoneVerificationConfirmResponse confirm(String verificationId, String code);

    /**
     * 회원가입의 DB 작업 전에 토큰과 연결된 검증 완료 번호를 조회한다.
     * 이 단계에서는 토큰을 소비하지 않아 이후 DB unique 검증과 저장을 먼저 수행할 수 있다.
     */
    String getVerifiedPhoneNumber(String signupToken);

    /**
     * DB flush 이후 가입 토큰을 Redis GETDEL로 한 번만 소비하고 조회했던 번호와 같은지 확인한다.
     * 소비 실패 예외는 호출자의 회원가입 트랜잭션까지 롤백시키는 계약이다.
     */
    void consumeSignupToken(String signupToken, String expectedPhoneNumber);
}
