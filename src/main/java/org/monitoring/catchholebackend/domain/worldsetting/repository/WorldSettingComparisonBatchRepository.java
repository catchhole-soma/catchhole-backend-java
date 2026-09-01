package org.monitoring.catchholebackend.domain.worldsetting.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonBatch;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonBatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorldSettingComparisonBatchRepository
        extends JpaRepository<WorldSettingComparisonBatch, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select batch
            from WorldSettingComparisonBatch batch
            where batch.id = :batchId
              and batch.work.id = :workId
            """)
    Optional<WorldSettingComparisonBatch> findByIdAndWorkIdForUpdate(
            @Param("batchId") UUID batchId,
            @Param("workId") UUID workId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select batch
            from WorldSettingComparisonBatch batch
            where batch.analysisJob.id = :analysisJobId
              and batch.status = :status
            order by batch.createdAt asc, batch.id asc
            """)
    List<WorldSettingComparisonBatch> findAllByAnalysisJobIdAndStatusForUpdate(
            @Param("analysisJobId") UUID analysisJobId,
            @Param("status") WorldSettingComparisonBatchStatus status
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            update WorldSettingComparisonBatch batch
            set batch.contextSnapshotJson = null,
                batch.rawCompletionJson = null
            where batch.sourceEpisode.id = :sourceEpisodeId
            """)
    int purgeSourceEvidenceBySourceEpisodeId(
            @Param("sourceEpisodeId") UUID sourceEpisodeId
    );
}
