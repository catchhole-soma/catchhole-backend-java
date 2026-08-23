package org.monitoring.catchholebackend.global.storage;

public record ObjectStoragePurgeResult(
        int targetCount,
        int deletedCount,
        int failedCount
) {

    public boolean isComplete() {
        return failedCount == 0;
    }
}
