package org.monitoring.catchholebackend.domain.validation.repository;

import java.time.Instant;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.validation.type.ValidationExecutionStatus;

/** #381 확정 설정을 잠그는 검수 실행의 scalar 상태. executionStatus는 QUEUED 또는 RUNNING이고 leaseExpiresAt은 RUNNING일 때만 있다. */
public record ValidationSettingsLockState(UUID reportId, UUID validationJobId, int episodeNo,
        ValidationExecutionStatus executionStatus, Instant leaseExpiresAt) {
}
