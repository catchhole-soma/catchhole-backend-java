package org.monitoring.catchholebackend.domain.analysis.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select analysisJob from AnalysisJob analysisJob join fetch analysisJob.work where analysisJob.id = :id")
    Optional<AnalysisJob> findByIdForUpdate(@Param("id") UUID id);

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
              and analysisJob.jobType not in (
                  org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.WORLD_SETTING_COMPARISON,
                  org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.CHARACTER_FACT_COMPARISON
              )
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
                      and analysisJob.jobType not in (
                          org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.WORLD_SETTING_COMPARISON,
                          org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.CHARACTER_FACT_COMPARISON
                      )
                    group by analysisJob.batch.id
                    order by max(analysisJob.createdAt) desc, analysisJob.batch.id desc
                    """,
            countQuery = """
                    select count(distinct analysisJob.batch.id)
                    from AnalysisJob analysisJob
                    where analysisJob.work.id = :workId
                      and analysisJob.batch is not null
                      and analysisJob.jobType not in (
                          org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.WORLD_SETTING_COMPARISON,
                          org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.CHARACTER_FACT_COMPARISON
                      )
                    """
    )
    Page<AnalysisBatchPageRow> findBatchPage(
            @Param("workId") UUID workId,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {"batch", "episode", "targetEpisodes"})
    @Query("""
            select analysisJob
            from AnalysisJob analysisJob
            where analysisJob.work.id = :workId
              and analysisJob.batch.id in :batchIds
              and analysisJob.jobType not in (
                  org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.WORLD_SETTING_COMPARISON,
                  org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.CHARACTER_FACT_COMPARISON
              )
            order by analysisJob.createdAt desc, analysisJob.id desc
            """)
    List<AnalysisJob> findAllByWorkIdAndBatchIdInOrderByCreatedAtDescIdDesc(
            @Param("workId") UUID workId,
            @Param("batchIds") Collection<UUID> batchIds
    );

    @Query("""
            select analysisJob.batch.id as batchId,
                   count(analysisJob) as activeComparisonCount
            from AnalysisJob analysisJob
            where analysisJob.work.id = :workId
              and analysisJob.batch.id in :batchIds
              and analysisJob.jobType = :jobType
              and analysisJob.status in :statuses
            group by analysisJob.batch.id
            """)
    List<AnalysisBatchActiveComparisonCounts> countActiveComparisonsByBatchIds(
            @Param("workId") UUID workId,
            @Param("batchIds") Collection<UUID> batchIds,
            @Param("jobType") AnalysisJobType jobType,
            @Param("statuses") Collection<AnalysisJobStatus> statuses
    );

    Optional<AnalysisJob> findFirstByBatchIdOrderByCreatedAtDesc(UUID batchId);

    @Query("""
            select min(episode.episodeNo) as episodeStartNo,
                   max(episode.episodeNo) as episodeEndNo,
                   count(distinct episode.id) as episodeCount
            from AnalysisJob analysisJob
            join analysisJob.targetEpisodes episode
            where analysisJob.work.id = :workId
              and analysisJob.batch.id = :batchId
            """)
    AnalysisJobEpisodeRange findEpisodeRangeByWorkIdAndBatchId(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId
    );

    Optional<AnalysisJob> findFirstByEpisodeIdAndBatchIdAndJobTypeNotInOrderByCreatedAtDesc(
            UUID episodeId,
            UUID batchId,
            Collection<AnalysisJobType> excludedJobTypes
    );

    @Query("""
            select analysisJob
            from AnalysisJob analysisJob
            left join fetch analysisJob.batch
            join fetch analysisJob.episode
            where analysisJob.batch.id in :batchIds
              and analysisJob.episode.id in :episodeIds
              and analysisJob.jobType not in (
                  org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.WORLD_SETTING_COMPARISON,
                  org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.CHARACTER_FACT_COMPARISON
              )
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

    boolean existsByBatchIdAndEpisodeIsNullAndJobTypeNotInAndStatusIn(
            UUID batchId,
            Collection<AnalysisJobType> excludedJobTypes,
            Collection<AnalysisJobStatus> statuses
    );

    boolean existsByEpisodeIdAndBatchIdAndStatusIn(
            UUID episodeId,
            UUID batchId,
            Collection<AnalysisJobStatus> statuses
    );

    boolean existsByEpisodeIdAndBatchIdAndJobTypeNotInAndStatusIn(
            UUID episodeId,
            UUID batchId,
            Collection<AnalysisJobType> excludedJobTypes,
            Collection<AnalysisJobStatus> statuses
    );

    Optional<AnalysisJob> findFirstByEpisodeIdAndBatchIdAndJobTypeAndStatusInOrderByCreatedAtDesc(
            UUID episodeId,
            UUID batchId,
            AnalysisJobType jobType,
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
              and analysisJob.jobType in :jobTypes
            order by analysisJob.createdAt asc, episode.episodeNo asc
            """)
    List<AnalysisJob> findClaimCandidates(
            @Param("status") AnalysisJobStatus status,
            @Param("jobTypes") Collection<AnalysisJobType> jobTypes,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select analysisJob
            from AnalysisJob analysisJob
            join fetch analysisJob.work
            left join fetch analysisJob.batch
            left join fetch analysisJob.worldSettingCandidate
            left join fetch analysisJob.settingCandidate
            where analysisJob.status = :status
              and analysisJob.jobType in :jobTypes
              and (
                    analysisJob.leaseToken is null
                    or analysisJob.leaseExpiresAt is null
                    or analysisJob.leaseExpiresAt <= :now
              )
            order by analysisJob.leaseExpiresAt asc, analysisJob.createdAt asc
            """)
    List<AnalysisJob> findExpiredLeaseCandidates(
            @Param("status") AnalysisJobStatus status,
            @Param("jobTypes") Collection<AnalysisJobType> jobTypes,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AnalysisJob> findFirstByWorldSettingCandidateIdAndStatusInOrderByCreatedAtDesc(
            UUID worldSettingCandidateId,
            Collection<AnalysisJobStatus> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AnalysisJob> findFirstBySettingCandidateIdAndStatusInOrderByCreatedAtDesc(
            UUID settingCandidateId,
            Collection<AnalysisJobStatus> statuses
    );

    boolean existsBySettingCandidateIdAndStatusIn(
            UUID settingCandidateId,
            Collection<AnalysisJobStatus> statuses
    );
}
