package org.monitoring.catchholebackend.domain.aitoken.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.aitoken.exception.AiTokenErrorCode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.global.exception.AppException;

@DisplayName("사용량 계정의 소수 단위 예약과 정산")
class AiTokenAccountTest {

    @Test
    @DisplayName("0.05 단위 사용을 20회 정산해도 누락이나 반올림 없이 1이 차감된다")
    void smallSettlementsAccumulateExactly() {
        var account = AiTokenAccount.create(mock(Member.class), 1);
        var unit = new BigDecimal("0.05");
        for (int i = 0; i < 20; i++) {
            account.reserve(unit);
            account.settle(unit, unit);
        }
        assertThat(account.getUsedTokens()).isEqualByComparingTo("1");
        assertThat(account.getReservedTokens()).isZero();
        assertThat(account.remainingTokens()).isZero();
        assertThatThrownBy(() -> account.reserve(unit)).isInstanceOfSatisfying(AppException.class,
                error -> assertThat(error.getResultCode()).isEqualTo(AiTokenErrorCode.AI_TOKEN_QUOTA_EXHAUSTED));
    }

    @Test
    @DisplayName("일부 예약 해제와 실제 정산 후에도 다른 요청의 소수 예약량은 남는다")
    void releaseAndSettlementPreserveOtherReservations() {
        var account = AiTokenAccount.create(mock(Member.class), 2);
        account.reserve(new BigDecimal("1.05"));
        account.reserve(new BigDecimal("0.20"));
        account.settle(new BigDecimal("1.05"), new BigDecimal("0.35"));
        assertThat(account.getUsedTokens()).isEqualByComparingTo("0.35");
        assertThat(account.getReservedTokens()).isEqualByComparingTo("0.20");
        assertThat(account.remainingTokens()).isEqualByComparingTo("1.45");
        account.release(new BigDecimal("0.20"));
        assertThat(account.remainingTokens()).isEqualByComparingTo("1.65");
        account.grant(1);
        assertThat(account.remainingTokens()).isEqualByComparingTo("2.65");
    }

    @Test
    @DisplayName("실사용이 예약을 초과해도 소수 사용량을 보존하고 남은 양만 0으로 제한한다")
    void settlementOverrunPreservesMeasuredCharge() {
        var account = AiTokenAccount.create(mock(Member.class), 1);
        account.reserve(new BigDecimal("0.50"));
        account.settle(new BigDecimal("0.50"), new BigDecimal("1.05"));
        assertThat(account.getUsedTokens()).isEqualByComparingTo("1.05");
        assertThat(account.getReservedTokens()).isZero();
        assertThat(account.remainingTokens()).isZero();
    }
}
