package org.monitoring.catchholebackend.domain.relationship.repository;

import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphEdge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RelationshipGraphEdgeRepository
        extends JpaRepository<RelationshipGraphEdge, UUID> {
    List<RelationshipGraphEdge> findAllByWorkId(UUID workId);

    Optional<RelationshipGraphEdge> findByWorkIdAndCanonicalKey(UUID workId, String canonicalKey);
}
