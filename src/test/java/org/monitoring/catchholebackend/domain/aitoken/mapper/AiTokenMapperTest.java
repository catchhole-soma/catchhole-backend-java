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
        var response = mapper.toResponse(account, "team@example.test");
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
        var response = mapper.toResponse(account, "team@example.test");
        assertThat(response.remainingTokens()).isEqualByComparingTo("0.05");
        assertThat(response.remainingPercent()).isZero();
        assertThat(response.exhausted()).isFalse();
        account.reserve(new BigDecimal("0.05"));
        assertThat(mapper.toResponse(account, "team@example.test").exhausted()).isTrue();
    }

    @Test
    @DisplayName("지급받은 순간의 잔량을 100%로 삼아 쓰는 만큼 줄어든다")
    void percentResetsToRemainingBalanceOnEachGrant() {
        var account = AiTokenAccount.create(mock(Member.class), 5_000_000);
        assertThat(percent(account)).isEqualTo(100.0);

        account.settle(0, 4_500_000);
        assertThat(percent(account)).isEqualTo(10.0);

        account.grant(10_000_000);
        assertThat(percent(account)).isEqualTo(100.0);

        account.settle(0, 2_000_000);
        assertThat(percent(account)).isEqualTo(80.95);
    }

    @Test
    @DisplayName("예약 중인 양은 비율에서 빠지고 해제되면 돌아온다")
    void reservationLowersPercentUntilReleased() {
        var account = AiTokenAccount.create(mock(Member.class), 1_000);
        account.reserve(250);
        assertThat(percent(account)).isEqualTo(75.0);
        account.release(250);
        assertThat(percent(account)).isEqualTo(100.0);
    }

    @Test
    @DisplayName("지급 때 예약 중이던 양이 해제되어 기준을 넘어도 100%를 넘지 않는다")
    void percentNeverExceedsOneHundred() {
        var account = AiTokenAccount.create(mock(Member.class), 1_000);
        account.reserve(400);
        account.grant(1_000);
        account.release(400);
        assertThat(account.remainingTokens()).isEqualByComparingTo("2000");
        assertThat(percent(account)).isEqualTo(100.0);
    }

    @Test
    @DisplayName("지급받은 적 없는 계정은 0%이고 기준이 비어 있는 계정의 잔량은 100%로 보여 준다")
    void handlesEmptyBasis() {
        var empty = AiTokenAccount.create(mock(Member.class), 0);
        var response = mapper.toResponse(empty, "team@example.test");
        assertThat(response.remainingPercent()).isZero();
        assertThat(response.exhausted()).isTrue();

        var missingBasis = AiTokenAccount.create(mock(Member.class), 1_000);
        org.springframework.test.util.ReflectionTestUtils.setField(missingBasis, "percentBasisTokens", BigDecimal.ZERO);
        assertThat(percent(missingBasis)).isEqualTo(100.0);
    }

    private double percent(AiTokenAccount account) {
        return mapper.toResponse(account, "team@example.test").remainingPercent();
    }
}
