package org.monitoring.catchholebackend.domain.relationship.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipMentionGroup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RelationshipMentionGroupRepository extends JpaRepository<RelationshipMentionGroup, UUID> {
    Optional<RelationshipMentionGroup> findByWorkIdAndId(UUID workId, UUID id);
    Optional<RelationshipMentionGroup> findByWorkIdAndRequestId(UUID workId, UUID requestId);
    List<RelationshipMentionGroup> findAllByWorkIdAndActiveTrueOrderByCreatedAtAscIdAsc(UUID workId);
}
