package org.monitoring.catchholebackend.domain.relationship.mapper;

import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipProcessingResponse;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipProcessingState;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipProcessingStatus;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.springframework.stereotype.Component;

@Component
public class RelationshipProcessingMapper {
    public RelationshipProcessingResponse toResponse(Work work, RelationshipProcessingState state) {
        long processed = state == null ? 0 : state.getProcessedVersion();
        boolean pending = work.getRelationshipInputVersion() > processed;
        boolean failed = pending && state != null && state.getFailureCode() != null
                && state.getAttemptedVersion() == work.getRelationshipInputVersion();
        var status = failed ? RelationshipProcessingStatus.FAILED
                : pending ? RelationshipProcessingStatus.PENDING
                : state == null ? RelationshipProcessingStatus.NEVER_PROCESSED : RelationshipProcessingStatus.SUCCEEDED;
        return new RelationshipProcessingResponse(status, work.getRelationshipInputVersion(), processed,
                state == null ? null : state.getLastProcessedAt(),
                failed ? state.getFailureCode() : null, failed ? state.getRetryAt() : null,
                state == null ? 0 : state.getAutoConfirmedCount(),
                state == null ? 0 : state.getReviewRequiredCount(),
                state == null ? 0 : state.getUnsupportedSourceCount());
    }
}
