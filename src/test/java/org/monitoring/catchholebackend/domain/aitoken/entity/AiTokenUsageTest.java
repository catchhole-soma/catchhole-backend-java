package org.monitoring.catchholebackend.domain.aitoken.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenPurpose;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageStatus;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("AI 호출 원장의 raw 사용량과 차감 정책")
class AiTokenUsageTest {

    @Test
    @DisplayName("작품 파기 취소는 예약 원장을 해제할 수 있다")
    void releaseAcceptsWorkPurgeCancellation() {
        AnalysisJob job = mock(AnalysisJob.class);
        Work work = mock(Work.class);
        Member member = mock(Member.class);
        when(job.getWork()).thenReturn(work);
        when(work.getMember()).thenReturn(member);
        AiTokenUsage usage = AiTokenUsage.reserve(
                UUID.randomUUID(),
                job,
                AiTokenPurpose.SETTING_EXTRACTION,
                1,
                "test-model",
                100
        );

        usage.release(AiTokenUsageOutcome.WORK_PURGE_CANCELED);

        assertThat(usage.getStatus()).isEqualTo(AiTokenUsageStatus.RELEASED);
        assertThat(usage.getOutcome()).isEqualTo(AiTokenUsageOutcome.WORK_PURGE_CANCELED);
    }

    @Test
    @DisplayName("Luna 예약과 정산은 raw 토큰을 보존하며 캐시 입력을 중복 차감하지 않는다")
    void lunaPreservesRawTokensAndConvertsInputPlusOutputOnce() {
        var usage = usage("gpt-6-luna", 100);
        assertThat(usage.getChargePolicyVersion()).isEqualTo("MODEL_WEIGHTED_V1");
        assertThat(usage.getChargeDenominator()).isEqualTo(20);
        assertThat(usage.getReservedTokens()).isEqualTo(100);
        assertThat(usage.getReservedCharge()).isEqualByComparingTo("5");
        assertThat(usage.getChargedTokens()).isNull();
        long raw = usage.settle(20, 10, 1, AiTokenUsageOutcome.SUCCESS);
        assertThat(raw).isEqualTo(21);
        assertThat(usage.getInputTokens()).isEqualTo(20);
        assertThat(usage.getCachedInputTokens()).isEqualTo(10);
        assertThat(usage.getOutputTokens()).isEqualTo(1);
        assertThat(usage.getChargedTokens()).isEqualByComparingTo("1.05");
    }

    @Test
    @DisplayName("기존 Luna 원장은 저장된 legacy 정책으로 늦게 정산하고 새 할인으로 재해석하지 않는다")
    void legacyLunaReservationKeepsItsPersistedRawPolicy() {
        var usage = usage("gpt-6-luna", 100);
        ReflectionTestUtils.setField(usage, "chargePolicyVersion", "LEGACY_RAW_V1");
        ReflectionTestUtils.setField(usage, "chargeDenominator", 1);
        ReflectionTestUtils.setField(usage, "reservedCharge", new BigDecimal("100"));
        assertThat(usage.settle(20, 10, 1, AiTokenUsageOutcome.SUCCESS)).isEqualTo(21);
        assertThat(usage.getChargedTokens()).isEqualByComparingTo("21");
        assertThat(usage.getReservedCharge()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("해제한 할인 원장은 측정 사용량 0을 만들지 않는다")
    void releaseKeepsUnknownMeasurementDistinctFromZeroUsage() {
        var usage = usage("gpt-6-luna", 21);
        usage.release(AiTokenUsageOutcome.USAGE_UNAVAILABLE);
        assertThat(usage.getReservedTokens()).isEqualTo(21);
        assertThat(usage.getReservedCharge()).isEqualByComparingTo("1.05");
        assertThat(usage.getChargedTokens()).isNull();
        assertThat(usage.getInputTokens()).isNull();
        assertThat(usage.getOutputTokens()).isNull();
    }

    @Test
    @DisplayName("전체 입력보다 많은 캐시 입력은 차감과 상태 전이 전에 거절한다")
    void invalidCachedSubsetCannotSettleDiscountedUsage() {
        var usage = usage("gpt-6-luna", 21);
        assertThatThrownBy(() -> usage.settle(10, 11, 1, AiTokenUsageOutcome.SUCCESS))
                .isInstanceOf(AppException.class);
        assertThat(usage.getStatus()).isEqualTo(AiTokenUsageStatus.RESERVED);
        assertThat(usage.getChargedTokens()).isNull();
    }

    private AiTokenUsage usage(String model, long rawTokens) {
        AnalysisJob job = mock(AnalysisJob.class);
        Work work = mock(Work.class);
        when(job.getWork()).thenReturn(work);
        when(work.getMember()).thenReturn(mock(Member.class));
        return AiTokenUsage.reserve(UUID.randomUUID(), job, AiTokenPurpose.SUBJECT_RESOLUTION,
                1, model, rawTokens);
    }
}
