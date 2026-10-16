package org.monitoring.catchholebackend.domain.aitoken.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenAccount;
import org.monitoring.catchholebackend.domain.member.entity.Member;

@DisplayName("소수 사용량 응답과 소진 비율 매핑")
class AiTokenMapperTest {
    private final AiTokenMapper mapper = new AiTokenMapper();

    @Test
    @DisplayName("소수 사용량과 예약량을 그대로 반환하고 비율을 계산한다")
    void keepsFractionalAccountBalances() {
        var account = AiTokenAccount.create(mock(Member.class), 1);
        account.settle(BigDecimal.ZERO, new BigDecimal("0.05"));
        account.reserve(new BigDecimal("0.20"));
        var response = mapper.toResponse(account, 1, "team@example.test");
        assertThat(response.grantedTokens()).isEqualByComparingTo("1");
        assertThat(response.usedTokens()).isEqualByComparingTo("0.05");
        assertThat(response.reservedTokens()).isEqualByComparingTo("0.20");
        assertThat(response.remainingTokens()).isEqualByComparingTo("0.75");
        assertThat(response.remainingPercent()).isEqualTo(75.0);
        assertThat(response.exhausted()).isFalse();
    }

    @Test
    @DisplayName("표시 비율이 0으로 반올림돼도 0.05 잔액을 소진으로 판정하지 않는다")
    void positiveFractionRemainsUsableEvenWhenDisplayedPercentRoundsToZero() {
        var account = AiTokenAccount.create(mock(Member.class), 4_000_000);
        account.settle(BigDecimal.ZERO, new BigDecimal("3999999.95"));
        var response = mapper.toResponse(account, 4_000_000, "team@example.test");
        assertThat(response.remainingTokens()).isEqualByComparingTo("0.05");
        assertThat(response.remainingPercent()).isZero();
        assertThat(response.exhausted()).isFalse();
        account.reserve(new BigDecimal("0.05"));
        assertThat(mapper.toResponse(account, 4_000_000, "team@example.test").exhausted()).isTrue();
    }

    @Test
    @DisplayName("추가 지급 뒤 비율은 100으로 제한하고 기본 제공량 0이면 실제 지급량을 기준으로 한다")
    void capsPercentageAndSupportsDisabledDefaultGrant() {
        var account = AiTokenAccount.create(mock(Member.class), 1);
        account.grant(1);
        account.settle(BigDecimal.ZERO, new BigDecimal("0.05"));
        assertThat(mapper.toResponse(account, 1, "team@example.test").remainingPercent()).isEqualTo(100.0);
        assertThat(mapper.toResponse(account, 0, "team@example.test").remainingPercent()).isEqualTo(97.5);
        var empty = mapper.toResponse(AiTokenAccount.create(mock(Member.class), 0), 0, "team@example.test");
        assertThat(empty.remainingPercent()).isZero();
        assertThat(empty.exhausted()).isTrue();
    }
}
