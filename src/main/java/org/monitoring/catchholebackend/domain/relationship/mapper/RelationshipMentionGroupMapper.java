package org.monitoring.catchholebackend.domain.relationship.mapper;

import java.util.Comparator;
import java.util.List;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipMentionGroupResponse;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipCandidate;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipMentionGroup;
import org.springframework.stereotype.Component;

@Component
public class RelationshipMentionGroupMapper {
    public RelationshipMentionGroupResponse toResponse(RelationshipMentionGroup group,
            List<RelationshipCandidate> members, List<String> reasons) {
        return new RelationshipMentionGroupResponse(group.getId(), group.getVersion(), group.getName(),
                group.isActive(), members.stream().map(RelationshipCandidate::getId)
                        .sorted(Comparator.comparing(Object::toString)).toList(), reasons);
    }
}
