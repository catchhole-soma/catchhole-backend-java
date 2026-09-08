package org.monitoring.catchholebackend.domain.auth.service;

import org.monitoring.catchholebackend.domain.auth.dto.response.EmailVerificationConfirmResponse;
import org.monitoring.catchholebackend.domain.auth.dto.response.EmailVerificationSendResponse;

/**
 * 이메일 소유 확인 흐름과 회원가입용 일회성 토큰의 수명주기를 관리한다.
 * 구현체는 인증번호·이메일·클라이언트 IP 원문을 로그나 Redis key에 노출하지 않아야 한다.
 */
public interface EmailVerificationService {

    /**
     * 가입되지 않은 이메일인지 확인하고 이메일·IP·전체 발송 제한을 통과하면 새 인증 흐름을 시작한다.
     * 같은 이메일로 다시 요청하면 이전 흐름을 폐기하며, 메일 발송은 중복 발송을 막기 위해 한 번만 시도한다.
     */
    EmailVerificationSendResponse sendEmailVerificationCode(String email, String clientIp);

    /**
     * 인증번호를 원자적으로 검증하고 성공 시 회원가입용 토큰을 발급한다.
     * 같은 인증 흐름을 다시 확인하면 토큰이 유효한 동안 기존 토큰을 반환해 여러 토큰이 생기지 않게 한다.
     */
    EmailVerificationConfirmResponse confirmEmailVerificationCode(String verificationId, String verificationCode);

    /**
     * 회원가입의 DB 작업 전에 토큰과 연결된 검증 완료 이메일을 조회한다.
     * 이 단계에서는 토큰을 소비하지 않아 이후 DB unique 검증과 저장을 먼저 수행할 수 있다.
     */
    String getVerifiedEmailBySignupToken(String signupToken);

    /**
     * DB flush 이후 가입 토큰을 Redis compare-and-delete로 한 번만 소비하고 조회했던 이메일과 같은지 확인한다.
     * 소비 실패 예외는 호출자의 회원가입 트랜잭션까지 롤백시키는 계약이다.
     */
    void consumeSignupToken(String signupToken, String expectedEmail);
}
