package org.monitoring.catchholebackend.domain.relationship.repository;

import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RelationshipGraphAnalysisRepository
        extends JpaRepository<RelationshipGraphAnalysis, UUID> {
    List<RelationshipGraphAnalysis> findAllByWorkIdOrderByEpisodeNoAscCreatedAtAscIdAsc(
            UUID workId);
}
