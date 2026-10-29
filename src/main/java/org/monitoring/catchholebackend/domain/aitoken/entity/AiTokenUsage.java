package org.monitoring.catchholebackend.domain.aitoken.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.monitoring.catchholebackend.domain.aitoken.exception.AiTokenErrorCode;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenChargePolicy;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenPurpose;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageStatus;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.chat.entity.ChatRun;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
import org.monitoring.catchholebackend.global.exception.AppException;

@Getter
@Entity
@Table(name = "ai_token_usages")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiTokenUsage extends BaseEntity {

    @Id
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "member_id", nullable = false, foreignKey = @ForeignKey(name = "fk_ai_token_usages_member"))
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "work_id", nullable = false, foreignKey = @ForeignKey(name = "fk_ai_token_usages_work"))
    private Work work;

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "analysis_job_id", foreignKey = @ForeignKey(name = "fk_ai_token_usages_analysis_job"))
    private AnalysisJob analysisJob;

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "chat_run_id")
    private ChatRun chatRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "validation_job_id")
    private org.monitoring.catchholebackend.domain.validation.entity.ValidationJob validationJob;
    @Column(name = "validation_lease_token", updatable = false) private UUID validationLeaseToken;
    @Column(name = "validation_generation", updatable = false) private Long validationGeneration;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AiTokenPurpose purpose;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiTokenUsageStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private AiTokenUsageOutcome outcome;

    @Column(name = "reserved_tokens", nullable = false)
    private long reservedTokens;

    @Column(name = "charge_policy_version", nullable = false, length = 40, updatable = false)
    private String chargePolicyVersion;

    @Column(name = "charge_denominator", nullable = false, updatable = false)
    private int chargeDenominator;

    @Column(name = "reserved_charge", nullable = false, precision = 22, scale = 2, updatable = false)
    private BigDecimal reservedCharge;

    @Column(name = "charged_tokens", precision = 22, scale = 2)
    private BigDecimal chargedTokens;

    @Column(name = "input_tokens")
    private Long inputTokens;

    @Column(name = "cached_input_tokens")
    private Long cachedInputTokens;

    @Column(name = "output_tokens")
    private Long outputTokens;

    private AiTokenUsage(UUID requestId, AnalysisJob job, AiTokenPurpose purpose, int attempt,
                         String modelName, long reservedTokens) {
        this.requestId = requestId;
        this.analysisJob = job;
        this.work = job.getWork();
        this.member = job.getWork().getMember();
        this.purpose = purpose;
        this.attempt = attempt;
        this.modelName = modelName;
        this.reservedTokens = reservedTokens;
        freezeChargePolicy(AiTokenChargePolicy.forModel(modelName));
        this.status = AiTokenUsageStatus.RESERVED;
    }

    public static AiTokenUsage reserve(UUID requestId, AnalysisJob job, AiTokenPurpose purpose,
                                       int attempt, String modelName, long reservedTokens) {
        return new AiTokenUsage(requestId, job, purpose, attempt, modelName, reservedTokens);
    }

    public static AiTokenUsage reserveChat(UUID id, ChatRun run, AiTokenPurpose purpose,
                                          String model, long tokens) {
        var usage = new AiTokenUsage();
        usage.requestId = id;
        usage.chatRun = run;
        usage.work = run.getWork();
        usage.member = run.getWork().getMember();
        usage.purpose = purpose;
        usage.attempt = Math.max(1, run.getGeneration());
        usage.modelName = model;
        usage.reservedTokens = tokens;
        usage.freezeChargePolicy(run.operational()
                ? AiTokenChargePolicy.servicePaid() : AiTokenChargePolicy.forModel(model));
        usage.status = AiTokenUsageStatus.RESERVED;
        return usage;
    }

    public static AiTokenUsage reserveValidation(UUID id, org.monitoring.catchholebackend.domain.validation.entity.ValidationJob job,
            AiTokenPurpose purpose, String model, long tokens) {
        var u=new AiTokenUsage();u.requestId=id;u.validationJob=job;u.validationLeaseToken=job.getLeaseToken();u.validationGeneration=job.getGeneration();
        u.work=job.getReport().getWork();u.member=job.getReport().getMember();u.purpose=purpose;u.attempt=job.getAttempt();u.modelName=model;
        u.reservedTokens=tokens;u.freezeChargePolicy(AiTokenChargePolicy.forModel(model));u.status=AiTokenUsageStatus.RESERVED;return u;
    }

    /** Lease loss releases the hold, but retains genuine call ownership for late measured usage. */
    public void markValidationUnknown(AiTokenUsageOutcome reason) {
        if(validationJob==null)throw new AppException(AiTokenErrorCode.AI_TOKEN_RESERVATION_CONFLICT);
        assertReserved();status=AiTokenUsageStatus.UNKNOWN;outcome=reason;
    }

    public void settleValidation(long input,long cached,long output,AiTokenUsageOutcome result) {
        if(validationJob==null||(status!=AiTokenUsageStatus.RESERVED&&status!=AiTokenUsageStatus.UNKNOWN))
            throw new AppException(AiTokenErrorCode.AI_TOKEN_RESERVATION_CONFLICT);
        if(input<0||cached<0||output<0||cached>input||input>Long.MAX_VALUE-output
                ||(result!=AiTokenUsageOutcome.SUCCESS&&result!=AiTokenUsageOutcome.FAILURE))throw new AppException(AiTokenErrorCode.AI_TOKEN_USAGE_INVALID);
        inputTokens=input;cachedInputTokens=cached;outputTokens=output;chargedTokens=chargePolicy().convert(input+output);outcome=result;status=AiTokenUsageStatus.SETTLED;
    }

    // UNKNOWN releases the hold without claiming zero usage; a late measured report may settle it.
    public void markChatUnknown() {
        assertReserved();
        this.status = AiTokenUsageStatus.UNKNOWN;
        this.outcome = AiTokenUsageOutcome.USAGE_UNAVAILABLE;
    }

    public long settleChat(long input, long cached, long output, AiTokenUsageOutcome outcome) {
        if (chatRun == null || (status != AiTokenUsageStatus.RESERVED && status != AiTokenUsageStatus.UNKNOWN)) {
            throw new AppException(AiTokenErrorCode.AI_TOKEN_RESERVATION_CONFLICT);
        }
        if (input < 0 || cached < 0 || output < 0 || cached > input || input > Long.MAX_VALUE - output
                || (outcome != AiTokenUsageOutcome.SUCCESS && outcome != AiTokenUsageOutcome.FAILURE)) {
            throw new AppException(AiTokenErrorCode.AI_TOKEN_USAGE_INVALID);
        }
        this.inputTokens = input;
        this.cachedInputTokens = cached;
        this.outputTokens = output;
        this.chargedTokens = chargePolicy().convert(input + output);
        this.outcome = outcome;
        this.status = AiTokenUsageStatus.SETTLED;
        return input + output;
    }

    public long settle(long inputTokens, long cachedInputTokens, long outputTokens,
                       AiTokenUsageOutcome outcome) {
        assertReserved();
        if (inputTokens < 0 || cachedInputTokens < 0 || outputTokens < 0
                || inputTokens > Long.MAX_VALUE - outputTokens
                || outcome == AiTokenUsageOutcome.USAGE_UNAVAILABLE || cachedInputTokens > inputTokens) {
            throw new AppException(AiTokenErrorCode.AI_TOKEN_USAGE_INVALID);
        }
        this.inputTokens = inputTokens;
        this.cachedInputTokens = cachedInputTokens;
        this.outputTokens = outputTokens;
        this.chargedTokens = chargePolicy().convert(inputTokens + outputTokens);
        this.outcome = outcome;
        this.status = AiTokenUsageStatus.SETTLED;
        return inputTokens + outputTokens;
    }

    public void release(AiTokenUsageOutcome outcome) {
        assertReserved();
        if (outcome != AiTokenUsageOutcome.USAGE_UNAVAILABLE
                && outcome != AiTokenUsageOutcome.WORKER_LEASE_EXPIRED
                && outcome != AiTokenUsageOutcome.WORK_PURGE_CANCELED) {
            throw new AppException(AiTokenErrorCode.AI_TOKEN_USAGE_INVALID);
        }
        this.outcome = outcome;
        this.status = AiTokenUsageStatus.RELEASED;
    }

    private void assertReserved() {
        if (status != AiTokenUsageStatus.RESERVED) {
            throw new AppException(AiTokenErrorCode.AI_TOKEN_RESERVATION_CONFLICT);
        }
    }

    private void freezeChargePolicy(AiTokenChargePolicy policy) {
        this.chargePolicyVersion = policy.version();
        this.chargeDenominator = policy.denominator();
        this.reservedCharge = policy.convert(reservedTokens);
    }

    private AiTokenChargePolicy chargePolicy() {
        return new AiTokenChargePolicy(chargePolicyVersion, chargeDenominator);
    }
}
