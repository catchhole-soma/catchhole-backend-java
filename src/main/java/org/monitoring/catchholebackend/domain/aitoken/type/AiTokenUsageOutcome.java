package org.monitoring.catchholebackend.domain.aitoken.type;

public enum AiTokenUsageOutcome {
    SUCCESS,
    FAILURE,
    USAGE_UNAVAILABLE,
    WORKER_LEASE_EXPIRED,
    WORK_PURGE_CANCELED
}
