package org.monitoring.catchholebackend.domain.character.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFactComparisonBatch;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonBatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CharacterFactComparisonBatchRepository
        extends JpaRepository<CharacterFactComparisonBatch, UUID> {

    boolean existsByAnalysisJobIdAndMatchedCharacterIdAndCanonicalFactTypeAndStatus(
            UUID analysisJobId,
            UUID matchedCharacterId,
            org.monitoring.catchholebackend.domain.character.type.CharacterFactType canonicalFactType,
            CharacterFactComparisonBatchStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select batch
            from CharacterFactComparisonBatch batch
            where batch.id = :batchId
              and batch.analysisJob.id = :analysisJobId
            """)
    Optional<CharacterFactComparisonBatch> findByIdAndAnalysisJobIdForUpdate(
            @Param("batchId") UUID batchId,
            @Param("analysisJobId") UUID analysisJobId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select batch
            from CharacterFactComparisonBatch batch
            where batch.analysisJob.id = :analysisJobId
              and batch.status = :status
            order by batch.createdAt asc, batch.id asc
            """)
    List<CharacterFactComparisonBatch> findAllByAnalysisJobIdAndStatusForUpdate(
            @Param("analysisJobId") UUID analysisJobId,
            @Param("status") CharacterFactComparisonBatchStatus status
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            update CharacterFactComparisonBatch batch
            set batch.rawCompletionJson = null
            where batch.sourceEpisode.id = :sourceEpisodeId
            """)
    int purgeSourceEvidenceBySourceEpisodeId(
            @Param("sourceEpisodeId") UUID sourceEpisodeId
    );
}
