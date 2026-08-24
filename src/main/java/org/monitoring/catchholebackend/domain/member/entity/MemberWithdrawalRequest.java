package org.monitoring.catchholebackend.domain.member.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.domain.member.type.MemberWithdrawalStatus;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Getter
@Entity
@Table(
        name = "member_withdrawal_requests",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_member_withdrawal_requests_member",
                columnNames = "member_id"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberWithdrawalRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // 회원 행을 hard delete한 뒤에도 최소 파기 감사 기록을 유지하기 위해 FK를 두지 않는다.
    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private MemberWithdrawalStatus status;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "retention_expires_at")
    private LocalDateTime retentionExpiresAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error_code", length = 80)
    private String lastErrorCode;

    private MemberWithdrawalRequest(Long memberId, LocalDateTime requestedAt) {
        this.memberId = memberId;
        this.status = MemberWithdrawalStatus.REQUESTED;
        this.requestedAt = requestedAt;
        this.nextAttemptAt = requestedAt;
    }

    public static MemberWithdrawalRequest request(Long memberId, LocalDateTime requestedAt) {
        return new MemberWithdrawalRequest(memberId, requestedAt);
    }

    public boolean isReady(LocalDateTime now) {
        return status != MemberWithdrawalStatus.COMPLETED && !nextAttemptAt.isAfter(now);
    }

    public void beginAttempt(LocalDateTime now) {
        this.status = MemberWithdrawalStatus.PROCESSING;
        if (processingStartedAt == null) {
            this.processingStartedAt = now;
        }
        this.nextAttemptAt = now;
        this.attemptCount++;
        this.lastErrorCode = null;
    }

    public void deferUntil(LocalDateTime nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
        this.lastErrorCode = null;
    }

    public void retryAfterFailure(String errorCode, LocalDateTime failedAt, LocalDateTime nextAttemptAt) {
        this.status = MemberWithdrawalStatus.PROCESSING;
        if (processingStartedAt == null) {
            this.processingStartedAt = failedAt;
        }
        this.attemptCount++;
        this.lastErrorCode = errorCode;
        this.nextAttemptAt = nextAttemptAt;
    }

    public void complete(LocalDateTime completedAt, LocalDateTime retentionExpiresAt) {
        this.status = MemberWithdrawalStatus.COMPLETED;
        this.completedAt = completedAt;
        this.retentionExpiresAt = retentionExpiresAt;
        this.nextAttemptAt = completedAt;
        this.lastErrorCode = null;
    }
}
