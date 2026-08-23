package org.monitoring.catchholebackend.domain.work.repository;

public record WorkPurgeDatabaseResult(
        int targetCount,
        int deletedCount,
        int failedCount
) {
}
