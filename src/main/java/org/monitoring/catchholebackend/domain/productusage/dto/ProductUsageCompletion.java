package org.monitoring.catchholebackend.domain.productusage.dto;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public record ProductUsageCompletion(UUID requestId, UUID workId, Long memberId,
        LocalDateTime memberCreatedAt, LocalDateTime requestedAt, LocalDateTime completedAt) {
    public ProductUsageCompletion {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(workId);
        Objects.requireNonNull(memberId);
        Objects.requireNonNull(memberCreatedAt);
        Objects.requireNonNull(requestedAt);
        Objects.requireNonNull(completedAt);
        if (completedAt.isBefore(requestedAt)) throw new IllegalArgumentException("접수 전 완료 기록은 집계할 수 없습니다.");
    }
}
