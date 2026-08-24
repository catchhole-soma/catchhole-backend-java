package org.monitoring.catchholebackend.domain.work.service;

public record MemberWorkPurgeProgress(
        long remainingWorkCount,
        int createdRequestCount,
        int retriedRequestCount
) {
}
