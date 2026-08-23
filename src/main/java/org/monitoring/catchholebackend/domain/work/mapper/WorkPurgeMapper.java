package org.monitoring.catchholebackend.domain.work.mapper;

import java.time.Duration;
import java.time.LocalDateTime;
import org.monitoring.catchholebackend.domain.work.dto.response.WorkPurgeResponse;
import org.monitoring.catchholebackend.domain.work.dto.response.WorkPurgeStoreResultResponse;
import org.monitoring.catchholebackend.domain.work.entity.WorkPurgeRequest;
import org.springframework.stereotype.Component;

@Component
public class WorkPurgeMapper {

    private static final Duration SLA = Duration.ofHours(24);

    public WorkPurgeResponse toResponse(WorkPurgeRequest request) {
        return new WorkPurgeResponse(
                request.getId(),
                request.getWorkId(),
                request.getStatus(),
                request.getRequestedAt(),
                request.getProcessingStartedAt(),
                request.getCompletedAt(),
                request.getAttemptCount(),
                request.isRetryable(),
                request.getLastErrorCode(),
                new WorkPurgeStoreResultResponse(
                        request.getS3TargetCount(),
                        request.getS3DeletedCount(),
                        request.getS3FailedCount()
                ),
                new WorkPurgeStoreResultResponse(
                        request.getDbTargetCount(),
                        request.getDbDeletedCount(),
                        request.getDbFailedCount()
                ),
                isSlaBreached(request)
        );
    }

    private boolean isSlaBreached(WorkPurgeRequest request) {
        LocalDateTime end = request.getCompletedAt() == null ? LocalDateTime.now() : request.getCompletedAt();
        return Duration.between(request.getRequestedAt(), end).compareTo(SLA) > 0;
    }
}
