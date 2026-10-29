package org.monitoring.catchholebackend.domain.aitoken.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenReleaseRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenReserveRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenSettleRequest;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenAccount;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenUsage;
import org.monitoring.catchholebackend.domain.aitoken.exception.AiTokenErrorCode;
import org.monitoring.catchholebackend.domain.aitoken.mapper.AiTokenMapper;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenAccountRepository;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenExtensionRequestRepository;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenGrantRepository;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenTotals;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenUsageRepository;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenPurpose;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageStatus;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobLeaseService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.config.ai.AiTokenProperties;
import org.monitoring.catchholebackend.global.exception.AppException;

@DisplayName("모델별 사용량 원장의 예약·정산·해제")
class AiTokenServiceImplTest {
    private final UUID jobId = UUID.randomUUID();
    private final UUID lease = UUID.randomUUID();
    private final AiTokenAccountRepository accounts = mock(AiTokenAccountRepository.class);
    private final AiTokenUsageRepository usages = mock(AiTokenUsageRepository.class);
    private final AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
    private final AnalysisJobLeaseService leases = mock(AnalysisJobLeaseService.class);
    private final AnalysisJob job = mock(AnalysisJob.class);
    private final Map<UUID, AiTokenUsage> ledger = new HashMap<>();
    private AiTokenAccount account;
    private AiTokenServiceImpl service;

    @BeforeEach
    void setUp() {
        Member member = mock(Member.class);
        Work work = mock(Work.class);
        when(member.getId()).thenReturn(42L);
        when(work.getMember()).thenReturn(member);
        when(job.getWork()).thenReturn(work);
        when(job.getId()).thenReturn(jobId);
        when(job.getStatus()).thenReturn(AnalysisJobStatus.RUNNING);
        account = AiTokenAccount.create(member, 50_000);
        when(accounts.findByMemberId(42L)).thenReturn(Optional.of(account));
        when(leases.getRunningAnalysisJobForUpdate(jobId, lease)).thenReturn(job);
        when(jobs.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
        when(usages.findByRequestId(any())).thenAnswer(call -> Optional.ofNullable(ledger.get(call.getArgument(0))));
        when(usages.findAnalysisJobIdByRequestId(any())).thenAnswer(call ->
                ledger.containsKey(call.getArgument(0)) ? Optional.of(jobId) : Optional.empty());
        when(usages.save(any())).thenAnswer(call -> {
            AiTokenUsage usage = call.getArgument(0);
            ledger.put(usage.getRequestId(), usage);
            return usage;
        });
        service = new AiTokenServiceImpl(accounts, mock(AiTokenExtensionRequestRepository.class),
                mock(AiTokenGrantRepository.class), usages, jobs, leases, mock(MemberRepository.class),
                new AiTokenProperties(4_000_000, "team@example.test", 4256, 2256), new AiTokenMapper());
    }

    @Test
    @DisplayName("Luna 할인 예약은 정확한 소수 잔액을 사용하고 같은 요청을 중복 예약하지 않는다")
    void discountedReservationIsExactAndIdempotent() {
        leaveBalance("1.05");
        var request = request("gpt-6-luna", 21);
        var first = service.reserve(request, lease);
        var retry = service.reserve(request, lease);
        assertThat(first).isEqualTo(retry);
        assertThat(first.reservedTokens()).isEqualTo(21);
        assertThat(account.getReservedTokens()).isEqualByComparingTo("1.05");
        assertThat(account.remainingTokens()).isZero();
        assertThat(ledger).hasSize(1);
        assertQuota(() -> service.reserve(request("gpt-6-luna", 1), lease));
        assertThat(ledger).hasSize(1);
    }

    @Test
    @DisplayName("같은 raw 예약이라도 Sol과 미등록 모델은 할인되지 않는다")
    void fullRateModelsCannotUseDiscountedBalance() {
        leaveBalance("1.05");
        assertQuota(() -> service.reserve(request("gpt-6.1-sol", 21), lease));
        assertQuota(() -> service.reserve(request("gpt-6-luna-preview", 21), lease));
        assertThat(account.getReservedTokens()).isZero();
        assertThat(ledger).isEmpty();
    }

    @Test
    @DisplayName("Sol·Luna raw 각 2만은 총 raw 4만을 보존하며 사용자 사용량은 2만1천이다")
    void mixedModelsKeepRawTotalsAndDiscountOnlyAccountCharge() {
        var sol = request("gpt-6.1-sol", 20_000);
        var luna = request("gpt-6-luna", 20_000);
        service.reserve(sol, lease);
        service.reserve(luna, lease);
        assertThat(account.getReservedTokens()).isEqualByComparingTo("21000");
        var measured = new AiTokenSettleRequest(16_000, 8_000, 4_000, AiTokenUsageOutcome.SUCCESS);
        service.settle(sol.requestId(), measured);
        service.settle(luna.requestId(), measured);
        service.settle(luna.requestId(), measured);
        assertThat(account.getUsedTokens()).isEqualByComparingTo("21000");
        assertThat(account.getReservedTokens()).isZero();
        assertThat(ledger.get(luna.requestId()).getInputTokens()).isEqualTo(16_000);
        assertThat(ledger.get(luna.requestId()).getCachedInputTokens()).isEqualTo(8_000);
        assertThat(ledger.get(luna.requestId()).getOutputTokens()).isEqualTo(4_000);
        assertThat(ledger.get(luna.requestId()).getChargedTokens()).isEqualByComparingTo("1000");
        assertThat(ledger.values().stream().mapToLong(u -> u.getInputTokens() + u.getOutputTokens()).sum())
                .isEqualTo(40_000);
    }

    @Test
    @DisplayName("실패 호출의 측정 사용량도 할인 정산하고 예약 차액을 돌려준다")
    void measuredFailureSettlesActualFractionAndReleasesReservationDifference() {
        var request = request("gpt-6-luna", 100);
        service.reserve(request, lease);
        service.settle(request.requestId(), new AiTokenSettleRequest(20, 10, 1, AiTokenUsageOutcome.FAILURE));
        assertThat(account.getUsedTokens()).isEqualByComparingTo("1.05");
        assertThat(account.getReservedTokens()).isZero();
        assertThat(ledger.get(request.requestId()).getReservedTokens()).isEqualTo(100);
        assertThat(ledger.get(request.requestId()).getReservedCharge()).isEqualByComparingTo("5");
        assertThat(ledger.get(request.requestId()).getChargedTokens()).isEqualByComparingTo("1.05");
    }

    @Test
    @DisplayName("반복 해제와 작업 일괄 해제는 할인 예약량만 반환하고 실사용을 만들지 않는다")
    void repeatedReleaseAndJobCleanupReleaseOnlyOutstandingDiscountedHolds() {
        var first = request("gpt-6-luna", 21);
        var second = request("gpt-6-luna", 1);
        service.reserve(first, lease);
        service.reserve(second, lease);
        var release = new AiTokenReleaseRequest(AiTokenUsageOutcome.USAGE_UNAVAILABLE);
        service.release(first.requestId(), release);
        service.release(first.requestId(), release);
        assertThat(account.getReservedTokens()).isEqualByComparingTo("0.05");
        when(usages.findAllByAnalysisJobIdAndStatus(jobId, AiTokenUsageStatus.RESERVED))
                .thenAnswer(call -> ledger.values().stream()
                        .filter(u -> u.getStatus() == AiTokenUsageStatus.RESERVED).toList());
        service.releaseReservedForAnalysisJob(jobId, AiTokenUsageOutcome.WORKER_LEASE_EXPIRED);
        service.releaseReservedForAnalysisJob(jobId, AiTokenUsageOutcome.WORKER_LEASE_EXPIRED);
        assertThat(account.getReservedTokens()).isZero();
        assertThat(account.getUsedTokens()).isZero();
        assertThat(ledger.values()).allMatch(u -> u.getStatus() == AiTokenUsageStatus.RELEASED);
    }

    @Test
    @DisplayName("모델 미정 사전 검사 통과 뒤에도 실제 모델 예약에서 잔액을 최종 확인한다")
    void preflightDoesNotPromiseThatAnUndiscountedModelCanReserve() {
        leaveBalance("212.80");
        assertThatCode(() -> service.ensureAnalysisCanStart(42L)).doesNotThrowAnyException();
        assertQuota(() -> service.reserve(request("gpt-6.1-sol", 4256), lease));
        service.reserve(request("gpt-6-luna", 4256), lease);
        assertThat(account.remainingTokens()).isZero();
    }

    @Test
    @DisplayName("사전검사는 분석·비교 각각의 환산 하한 아래 잔액을 거절한다")
    void preflightRejectsBalancesBelowBothMinimumCharges() {
        leaveBalance("112.75");
        assertQuota(() -> service.ensureAnalysisCanStart(42L));
        assertQuota(() -> service.ensureComparisonCanStart(42L));
        account.grant(1);
        assertThatCode(() -> service.ensureComparisonCanStart(42L)).doesNotThrowAnyException();
        assertQuota(() -> service.ensureAnalysisCanStart(42L));
    }

    @Test
    @DisplayName("종료 작업에 늦게 정산해도 Job에는 환산값 대신 raw 입출력 합계를 기록한다")
    void lateSettlementSynchronizesRawJobTotals() {
        var request = request("gpt-6-luna", 20_000);
        service.reserve(request, lease);
        when(job.getStatus()).thenReturn(AnalysisJobStatus.FAILED);
        var totals = mock(AiTokenTotals.class);
        when(totals.getInputTokens()).thenReturn(32_000L);
        when(totals.getOutputTokens()).thenReturn(8_000L);
        when(usages.sumSettledTokensByAnalysisJobId(jobId, AiTokenUsageStatus.SETTLED)).thenReturn(totals);
        service.settle(request.requestId(), new AiTokenSettleRequest(16_000, 8_000, 4_000, AiTokenUsageOutcome.SUCCESS));
        verify(job).updateTokenCounts(32_000, 8_000);
        assertThat(service.getAnalysisJobTokenTotals(jobId)).containsExactly(32_000, 8_000);
        assertThat(account.getUsedTokens()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("분석 lease는 검수 전용 목적의 원장을 만들 수 없다")
    void analysisLeaseRejectsEveryValidationPurpose() {
        for(var purpose:AiTokenPurpose.values())if(purpose.name().startsWith("VALIDATION_")) {
            assertThatThrownBy(()->service.reserve(new AiTokenReserveRequest(UUID.randomUUID(),jobId,purpose,1,"gpt-6.1-terra",100),lease))
                    .isInstanceOfSatisfying(AppException.class,error->assertThat(error.getResultCode()).isEqualTo(AiTokenErrorCode.AI_TOKEN_USAGE_INVALID));
        }
        assertThat(ledger).isEmpty();assertThat(account.getReservedTokens()).isZero();
    }

    private AiTokenReserveRequest request(String model, long rawTokens) {
        return new AiTokenReserveRequest(UUID.randomUUID(), jobId, AiTokenPurpose.SUBJECT_RESOLUTION,
                1, model, rawTokens);
    }

    private void leaveBalance(String remaining) {
        account.settle(BigDecimal.ZERO, account.remainingTokens().subtract(new BigDecimal(remaining)));
    }

    private void assertQuota(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AppException.class,
                error -> assertThat(error.getResultCode()).isEqualTo(AiTokenErrorCode.AI_TOKEN_QUOTA_EXHAUSTED));
    }
}
