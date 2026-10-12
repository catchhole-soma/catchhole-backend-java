package org.monitoring.catchholebackend.domain.chat.type;

public enum ChatState {
    QUEUED,
    RUNNING,
    SETTLEMENT_PENDING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    INVALIDATED;

    public boolean active() {
        return this == QUEUED || this == RUNNING || this == SETTLEMENT_PENDING;
    }
}
