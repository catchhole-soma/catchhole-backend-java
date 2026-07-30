package org.monitoring.catchholebackend.domain.analysis.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID> {

    @EntityGraph(attributePaths = "targetEpisodes")
    Optional<AnalysisJob> findByIdAndWorkId(UUID id, UUID workId);

    @Query("""
            select distinct analysisJob
            from AnalysisJob analysisJob
            join fetch analysisJob.work
            left join fetch analysisJob.batch
            left join fetch analysisJob.episode
            left join fetch analysisJob.targetEpisodes
            where analysisJob.work.id = :workId
            order by analysisJob.createdAt desc
            """)
    List<AnalysisJob> findAllWithTargetsByWorkIdOrderByCreatedAtDesc(@Param("workId") UUID workId);

    @Query(
            value = """
                    select analysisJob.batch.id as batchId,
                           min(analysisJob.createdAt) as firstRequestedAt,
                           max(analysisJob.createdAt) as lastRequestedAt
                    from AnalysisJob analysisJob
                    where analysisJob.work.id = :workId
                      and analysisJob.batch is not null
                    group by analysisJob.batch.id
                    order by max(analysisJob.createdAt) desc, analysisJob.batch.id desc
                    """,
            countQuery = """
                    select count(distinct analysisJob.batch.id)
                    from AnalysisJob analysisJob
                    where analysisJob.work.id = :workId
                      and analysisJob.batch is not null
                    """
    )
    Page<AnalysisBatchPageRow> findBatchPage(
            @Param("workId") UUID workId,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {"batch", "episode", "targetEpisodes"})
    List<AnalysisJob> findAllByWorkIdAndBatchIdInOrderByCreatedAtDescIdDesc(
            UUID workId,
            Collection<UUID> batchIds
    );

    Optional<AnalysisJob> findFirstByBatchIdOrderByCreatedAtDesc(UUID batchId);

    @Query("""
            select min(episode.episodeNo) as episodeStartNo,
                   max(episode.episodeNo) as episodeEndNo,
                   count(distinct episode.id) as episodeCount
            from AnalysisJob analysisJob
            join analysisJob.episode episode
            where analysisJob.work.id = :workId
              and analysisJob.batch.id = :batchId
            """)
    AnalysisJobEpisodeRange findEpisodeRangeByWorkIdAndBatchId(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId
    );

    Optional<AnalysisJob> findFirstByEpisodeIdAndBatchIdOrderByCreatedAtDesc(UUID episodeId, UUID batchId);

    @Query("""
            select analysisJob
            from AnalysisJob analysisJob
            left join fetch analysisJob.batch
            join fetch analysisJob.episode
            where analysisJob.batch.id in :batchIds
              and analysisJob.episode.id in :episodeIds
            order by analysisJob.createdAt desc
            """)
    List<AnalysisJob> findAllRelevantForEpisodeSummaries(
            @Param("batchIds") Collection<UUID> batchIds,
            @Param("episodeIds") Collection<UUID> episodeIds
    );

    boolean existsByBatchIdAndStatusIn(UUID batchId, Collection<AnalysisJobStatus> statuses);

    boolean existsByBatchIdAndEpisodeIsNullAndStatusIn(
            UUID batchId,
            Collection<AnalysisJobStatus> statuses
    );

    boolean existsByEpisodeIdAndBatchIdAndStatusIn(
            UUID episodeId,
            UUID batchId,
            Collection<AnalysisJobStatus> statuses
    );

    Optional<AnalysisJob> findFirstByEpisodeIdAndBatchIdAndStatusInOrderByCreatedAtDesc(
            UUID episodeId,
            UUID batchId,
            Collection<AnalysisJobStatus> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select analysisJob
            from AnalysisJob analysisJob
            join fetch analysisJob.work
            left join fetch analysisJob.batch
            left join analysisJob.episode episode
            where analysisJob.status = :status
            order by analysisJob.createdAt asc, episode.episodeNo asc
            """)
    List<AnalysisJob> findClaimCandidates(
            @Param("status") AnalysisJobStatus status,
            Pageable pageable
    );
}
