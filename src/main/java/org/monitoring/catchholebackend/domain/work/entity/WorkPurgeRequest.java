package org.monitoring.catchholebackend.domain.work.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.domain.work.type.WorkPurgeStatus;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Getter
@Entity
@Table(name = "work_purge_requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkPurgeRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // 작품 행이 삭제된 뒤에도 요청 상태를 조회하기 위해 FK 없이 원래 작품 ID를 보존한다.
    @Column(name = "work_id", nullable = false, updatable = false)
    private UUID workId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private WorkPurgeStatus status;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    // 실행 중이던 Worker heartbeat가 취소를 관찰할 수 있도록 실제 삭제를 늦추는 시각이다.
    @Column(name = "worker_drain_until")
    private LocalDateTime workerDrainUntil;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "retention_expires_at")
    private LocalDateTime retentionExpiresAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "retryable", nullable = false)
    private boolean retryable;

    // 사용자에게 내부 예외 메시지를 노출하지 않도록 정규화한 실패 code만 저장한다.
    @Column(name = "last_error_code", length = 80)
    private String lastErrorCode;

    @Column(name = "s3_target_count", nullable = false)
    private int s3TargetCount;

    @Column(name = "s3_deleted_count", nullable = false)
    private int s3DeletedCount;

    @Column(name = "s3_failed_count", nullable = false)
    private int s3FailedCount;

    @Column(name = "db_target_count", nullable = false)
    private int dbTargetCount;

    @Column(name = "db_deleted_count", nullable = false)
    private int dbDeletedCount;

    @Column(name = "db_failed_count", nullable = false)
    private int dbFailedCount;

    private WorkPurgeRequest(Long memberId, UUID workId, LocalDateTime workerDrainUntil) {
        this.memberId = memberId;
        this.workId = workId;
        this.status = WorkPurgeStatus.REQUESTED;
        this.requestedAt = LocalDateTime.now();
        this.workerDrainUntil = workerDrainUntil;
    }

    public static WorkPurgeRequest request(Long memberId, UUID workId, LocalDateTime workerDrainUntil) {
        return new WorkPurgeRequest(memberId, workId, workerDrainUntil);
    }

    public boolean isReady(LocalDateTime now) {
        return status == WorkPurgeStatus.REQUESTED
                && (workerDrainUntil == null || !workerDrainUntil.isAfter(now));
    }

    public void startProcessing() {
        this.status = WorkPurgeStatus.PROCESSING;
        this.processingStartedAt = LocalDateTime.now();
        this.attemptCount++;
        this.retryable = false;
        this.lastErrorCode = null;
    }

    public void recordStorageResult(int targetCount, int deletedCount, int failedCount) {
        this.s3TargetCount = Math.max(this.s3TargetCount, this.s3DeletedCount + targetCount);
        this.s3DeletedCount += deletedCount;
        this.s3FailedCount = failedCount;
    }

    public void recordDatabaseResult(int targetCount, int deletedCount, int failedCount) {
        this.dbTargetCount = targetCount;
        this.dbDeletedCount = deletedCount;
        this.dbFailedCount = failedCount;
    }

    public void complete(LocalDateTime retentionExpiresAt) {
        this.status = WorkPurgeStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.retentionExpiresAt = retentionExpiresAt;
        this.retryable = false;
        this.lastErrorCode = null;
    }

    public void fail(String errorCode, boolean partialFailure) {
        this.status = partialFailure ? WorkPurgeStatus.PARTIAL_FAILED : WorkPurgeStatus.FAILED;
        this.retryable = true;
        this.lastErrorCode = errorCode;
    }

    public void retry() {
        if (!status.canRetry()) {
            return;
        }
        this.status = WorkPurgeStatus.REQUESTED;
        this.retryable = false;
        this.lastErrorCode = null;
        this.processingStartedAt = null;
        this.workerDrainUntil = null;
    }

    public void recoverStaleProcessing(String errorCode) {
        if (status != WorkPurgeStatus.PROCESSING) {
            return;
        }
        fail(errorCode, s3DeletedCount > 0 || dbDeletedCount > 0);
    }
}
