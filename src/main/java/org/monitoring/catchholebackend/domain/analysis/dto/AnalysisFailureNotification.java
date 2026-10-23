package org.monitoring.catchholebackend.domain.analysis.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** 발송에 필요한 식별자와 고정 코드만 포함한다. Entity/원시 오류는 전송하지 않는다. */
public record AnalysisFailureNotification(UUID id, UUID workId, Long memberId, UUID jobId,
        UUID episodeId, Integer episodeNo, String failureKind, String failureCode,
        int failureCount, LocalDateTime occurredAt, int attempts, UUID leaseToken) {
}
