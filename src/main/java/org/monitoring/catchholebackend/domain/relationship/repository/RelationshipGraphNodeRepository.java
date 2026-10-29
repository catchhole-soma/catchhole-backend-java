package org.monitoring.catchholebackend.domain.relationship.repository;

import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RelationshipGraphNodeRepository
        extends JpaRepository<RelationshipGraphNode, UUID> {
    List<RelationshipGraphNode> findAllByWorkIdOrderByFirstEpisodeNoAscIdAsc(UUID workId);
}
