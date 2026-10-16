package org.monitoring.catchholebackend.domain.aitoken.type;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("모델별 사용자 사용량 환산 정책")
class AiTokenChargePolicyTest {

    @Test
    @DisplayName("Luna의 1토큰 20회와 20토큰 1회는 같은 1 사용량으로 환산한다")
    void smallCallsPreserveFractionWithoutPerCallRounding() {
        var policy = AiTokenChargePolicy.forModel("gpt-6-luna");
        assertThat(policy.denominator()).isEqualTo(20);
        assertThat(policy.convert(1)).isEqualByComparingTo("0.05");
        BigDecimal split = BigDecimal.ZERO;
        for (int i = 0; i < 20; i++) split = split.add(policy.convert(1));
        assertThat(split).isEqualByComparingTo(policy.convert(20));
        assertThat(split).isEqualByComparingTo("1");
        assertThat(policy.convert(Long.MAX_VALUE)).isEqualByComparingTo("461168601842738790.35");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"gpt-6.1-sol", "gpt-6-luna-preview", "gpt-5.6-luna", "GPT-6-LUNA", " gpt-6-luna", "unknown"})
    @DisplayName("정확히 gpt-6-luna가 아닌 모델은 기존 1배를 유지한다")
    void onlyExactLunaNameReceivesDiscount(String model) {
        var policy = AiTokenChargePolicy.forModel(model);
        assertThat(policy.denominator()).isEqualTo(1);
        assertThat(policy.convert(21)).isEqualByComparingTo("21");
    }

    @Test
    @DisplayName("과거 원장과 서비스 부담 정책은 새 모델별 정책과 구분한다")
    void legacyAndServicePaidRemainSeparatePolicies() {
        var current = AiTokenChargePolicy.forModel("gpt-6-luna");
        assertThat(AiTokenChargePolicy.legacy().version()).isNotEqualTo(current.version());
        assertThat(AiTokenChargePolicy.legacy().convert(21)).isEqualByComparingTo("21");
        assertThat(AiTokenChargePolicy.servicePaid().convert(21)).isZero();
    }

    @Test
    @DisplayName("모델 미확정 사전 검사는 가능한 최소 환산량으로 계산한다")
    void preflightUsesMinimumPossibleCharge() {
        assertThat(AiTokenChargePolicy.minimumCharge(4256)).isEqualByComparingTo("212.8");
        assertThat(AiTokenChargePolicy.minimumCharge(2256)).isEqualByComparingTo("112.8");
    }
}
