package org.monitoring.catchholebackend.domain.validation.repository;

import java.time.Instant;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.validation.type.ValidationExecutionStatus;
import org.monitoring.catchholebackend.domain.validation.type.ValidationSettingsLockReason;

/**
 * #381 확정 설정을 잠그는 검수 실행의 scalar 상태. executionStatus는 QUEUED·RUNNING(실행 단계) 또는 COMPLETED(검토 단계)이고
 * leaseExpiresAt은 RUNNING일 때만 의미가 있다(COMPLETED Job에는 마지막 lease가 남아 있을 수 있다).
 */
public record ValidationSettingsLockState(UUID reportId, UUID validationJobId, int episodeNo,
        ValidationExecutionStatus executionStatus, Instant leaseExpiresAt) {
    public ValidationSettingsLockReason reason() {
        return executionStatus == ValidationExecutionStatus.COMPLETED ? ValidationSettingsLockReason.REVIEW : ValidationSettingsLockReason.EXECUTION;
    }
}
