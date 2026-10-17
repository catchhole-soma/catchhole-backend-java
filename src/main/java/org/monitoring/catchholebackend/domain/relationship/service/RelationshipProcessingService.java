package org.monitoring.catchholebackend.domain.relationship.service;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipProcessingResponse;

public interface RelationshipProcessingService {
    RelationshipProcessingResponse getStatus(Long memberId, UUID workId);
    RelationshipProcessingResponse requestProcessing(Long memberId, UUID workId);
    void process(UUID workId);
    void recordFailure(UUID workId);
}
