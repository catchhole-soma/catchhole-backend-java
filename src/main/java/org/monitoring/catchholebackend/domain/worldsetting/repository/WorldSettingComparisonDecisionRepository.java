package org.monitoring.catchholebackend.domain.worldsetting.repository;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecision;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorldSettingComparisonDecisionRepository
        extends JpaRepository<WorldSettingComparisonDecision, UUID> {

    @EntityGraph(attributePaths = {"targetWorldSetting"})
    List<WorldSettingComparisonDecision> findAllByComparisonBatchIdOrderByDecisionRefAscIdAsc(
            UUID comparisonBatchId
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            update WorldSettingComparisonDecision decision
            set decision.comparisonReason = :purgedReason,
                decision.rawComparisonJson = null
            where decision.comparisonBatch.sourceEpisode.id = :sourceEpisodeId
            """)
    int purgeSourceEvidenceBySourceEpisodeId(
            @Param("sourceEpisodeId") UUID sourceEpisodeId,
            @Param("purgedReason") String purgedReason
    );
}
