package org.monitoring.catchholebackend.domain.relationship.type;

public enum RelationshipBackfillItemState {
    WAITING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED
}
