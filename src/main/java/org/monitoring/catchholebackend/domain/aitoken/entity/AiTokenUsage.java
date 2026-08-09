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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.monitoring.catchholebackend.domain.aitoken.exception.AiTokenErrorCode;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenPurpose;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageStatus;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "analysis_job_id", nullable = false, foreignKey = @ForeignKey(name = "fk_ai_token_usages_analysis_job"))
    private AnalysisJob analysisJob;

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
        this.status = AiTokenUsageStatus.RESERVED;
    }

    public static AiTokenUsage reserve(UUID requestId, AnalysisJob job, AiTokenPurpose purpose,
                                       int attempt, String modelName, long reservedTokens) {
        return new AiTokenUsage(requestId, job, purpose, attempt, modelName, reservedTokens);
    }

    public long settle(long inputTokens, long cachedInputTokens, long outputTokens,
                       AiTokenUsageOutcome outcome) {
        assertReserved();
        if (outcome == AiTokenUsageOutcome.USAGE_UNAVAILABLE || cachedInputTokens > inputTokens) {
            throw new AppException(AiTokenErrorCode.AI_TOKEN_USAGE_INVALID);
        }
        this.inputTokens = inputTokens;
        this.cachedInputTokens = cachedInputTokens;
        this.outputTokens = outputTokens;
        this.outcome = outcome;
        this.status = AiTokenUsageStatus.SETTLED;
        return inputTokens + outputTokens;
    }

    public void release(AiTokenUsageOutcome outcome) {
        assertReserved();
        if (outcome != AiTokenUsageOutcome.USAGE_UNAVAILABLE
                && outcome != AiTokenUsageOutcome.WORKER_LEASE_EXPIRED) {
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
}
