package org.monitoring.catchholebackend.domain.relationship.repository;

import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipBackfillWork;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RelationshipBackfillWorkRepository
        extends JpaRepository<RelationshipBackfillWork, UUID> {}
