package org.monitoring.catchholebackend.domain.relationship.service;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipDecisionRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipMentionGroupCreateRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipMentionGroupResponse;

public interface RelationshipMentionGroupService {
    RelationshipMentionGroupResponse createMentionGroup(Long memberId, UUID workId, RelationshipMentionGroupCreateRequest request);
    List<RelationshipMentionGroupResponse> getMentionGroups(Long memberId, UUID workId);
    RelationshipMentionGroupResponse getMentionGroup(Long memberId, UUID workId, UUID groupId);
    RelationshipMentionGroupResponse ungroupMentions(Long memberId, UUID workId, UUID groupId, RelationshipDecisionRequest request);
}
