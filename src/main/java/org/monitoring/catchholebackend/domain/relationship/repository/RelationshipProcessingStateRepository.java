package org.monitoring.catchholebackend.domain.relationship.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipProcessingState;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RelationshipProcessingStateRepository extends JpaRepository<RelationshipProcessingState, UUID> {
    Optional<RelationshipProcessingState> findByWorkId(UUID workId);

    @Query("""
            select new org.monitoring.catchholebackend.domain.relationship.repository.RelationshipProcessingInput(
                w.id, w.relationshipInputVersion)
            from Work w left join RelationshipProcessingState s on s.work.id = w.id
            where w.lifecycleStatus = org.monitoring.catchholebackend.domain.work.type.WorkLifecycleStatus.ACTIVE
              and w.relationshipInputVersion > coalesce(s.processedVersion, 0)
              and (s.retryAt is null or s.retryAt <= :now or w.relationshipInputVersion > s.attemptedVersion)
            order by w.updatedAt asc, w.id asc
            """)
    List<RelationshipProcessingInput> findReadyInputs(@Param("now") LocalDateTime now, Pageable pageable);
}
