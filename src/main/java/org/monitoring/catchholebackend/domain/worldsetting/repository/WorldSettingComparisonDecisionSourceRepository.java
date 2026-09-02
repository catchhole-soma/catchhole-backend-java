package org.monitoring.catchholebackend.domain.worldsetting.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecisionSource;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorldSettingComparisonDecisionSourceRepository
        extends JpaRepository<WorldSettingComparisonDecisionSource, UUID> {

    @EntityGraph(attributePaths = {"candidate", "comparisonDecision"})
    List<WorldSettingComparisonDecisionSource>
            findAllByComparisonBatchIdOrderByCandidateRefAscIdAsc(UUID comparisonBatchId);

    @EntityGraph(attributePaths = {"candidate", "comparisonDecision"})
    List<WorldSettingComparisonDecisionSource> findAllByComparisonDecisionIdIn(
            Collection<UUID> comparisonDecisionIds
    );

    long countByComparisonDecisionId(UUID comparisonDecisionId);
}
