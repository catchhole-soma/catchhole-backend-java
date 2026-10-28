package org.monitoring.catchholebackend.domain.analysis.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 실패 당시의 안전한 snapshot만 포함한다. 현재 Entity/원시 오류는 전송하지 않는다. */
public record AnalysisFailureNotification(UUID id, UUID workId, Long memberId, UUID jobId,
        UUID episodeId, Integer episodeNo, String failureKind, String failureCode,
        int failureCount, LocalDateTime occurredAt, int attempts, UUID leaseToken,
        AnalysisFailureDiagnostics failureDiagnostics, UUID executionJobId, UUID sourceAnalysisJobId,
        UUID comparisonBatchId, String jobType, List<Integer> targetEpisodeNos) {
    public AnalysisFailureNotification {
        targetEpisodeNos = targetEpisodeNos == null ? null : List.copyOf(targetEpisodeNos);
    }

    /** 배포 전 outbox row와 진단을 모르는 호출자의 호환 경계. 실행을 추정하지 않는다. */
    public AnalysisFailureNotification(UUID id, UUID workId, Long memberId, UUID jobId,
            UUID episodeId, Integer episodeNo, String failureKind, String failureCode,
            int failureCount, LocalDateTime occurredAt, int attempts, UUID leaseToken) {
        this(id, workId, memberId, jobId, episodeId, episodeNo, failureKind, failureCode,
                failureCount, occurredAt, attempts, leaseToken, null, null, null, null, null, null);
    }
}
