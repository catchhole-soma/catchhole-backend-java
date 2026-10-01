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

    boolean existsByWork_Member_Id(Long memberId);

    @Query("""
            select max(coalesce(sourceEpisode.episodeNo, candidateEpisode.episodeNo))
            from CharacterFact fact
            left join fact.sourceEpisode sourceEpisode
            left join fact.settingCandidate candidate
            left join candidate.episode candidateEpisode
            where fact.workCharacter.work.id = :workId
            """)
    Integer findLatestCharacterFactSourceEpisodeNo(@Param("workId") UUID workId);

    @Query("""
            select max(episode.episodeNo) from WorkCharacter character
            join Episode episode on episode.id = character.firstAppearanceEpisodeId
            where character.work.id = :workId
            """)
    Integer findLatestCharacterFirstAppearanceEpisodeNo(@Param("workId") UUID workId);

    @Query("""
            select max(candidate.episode.episodeNo) from SettingCandidate candidate
            where candidate.work.id = :workId
              and candidate.reviewStatus = org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.CONFIRMED
            """)
    Integer findLatestConfirmedCharacterSourceEpisodeNo(@Param("workId") UUID workId);

    @Query("""
            select max(candidate.sourceEpisode.episodeNo) from WorldSettingCandidate candidate
            where candidate.work.id = :workId
              and candidate.reviewStatus = org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.CONFIRMED
            """)
    Integer findLatestConfirmedWorldSourceEpisodeNo(@Param("workId") UUID workId);

    @Query("select candidate.id from SettingCandidate candidate where candidate.analysisJob.id = :jobId")
    List<UUID> findCharacterSourceCandidateIds(@Param("jobId") UUID jobId);

    @Query("select candidate.id from WorldSettingCandidate candidate where candidate.analysisJob.id = :jobId")
    List<UUID> findWorldSourceCandidateIds(@Param("jobId") UUID jobId);

    List<AnalysisJob> findAllByAnalysisRunIdAndRunGenerationOrderByRunSequenceAsc(UUID runId, Long generation);

    @Query("""
            select job from AnalysisJob job
            join fetch job.episode episode
            join fetch job.work
            where job.work.id = :workId
              and job.jobType = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION
              and episode.episodeNo < :sourceEpisodeNo
            order by job.createdAt desc, job.id desc
            """)
    List<AnalysisJob> findEarlierExtractionJobsForReferences(@Param("workId") UUID workId,
            @Param("sourceEpisodeNo") int sourceEpisodeNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select job from AnalysisJob job
            where job.analysisRunId = :runId and job.runGeneration = :generation
              and job.runSequence >= :sequence
            order by job.runSequence asc
            """)
    List<AnalysisJob> findRunTailForUpdate(@Param("runId") UUID runId,
            @Param("generation") Long generation, @Param("sequence") int sequence);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select job from AnalysisJob job
            where job.work.id = :workId
              and job.analysisMode = org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode.ORDERED_PROVISIONAL
              and job.journalStatus <> org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus.INVALIDATED
              and (:sourceEpisodeNo is null or job.sourceEpisodeNo >= :sourceEpisodeNo)
            order by job.analysisRunId, job.runGeneration, job.runSequence
            """)
    List<AnalysisJob> findAffectedOrderedJobsForUpdate(@Param("workId") UUID workId,
            @Param("sourceEpisodeNo") Integer sourceEpisodeNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select job from AnalysisJob job
            where job.work.id = :workId
              and job.analysisMode = org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode.ORDERED_PROVISIONAL
              and job.sourceEpisodeNo >= :sourceEpisodeNo
            order by job.analysisRunId, job.runGeneration, job.runSequence
            """)
    List<AnalysisJob> findOrderedJobsForSourcePurgeForUpdate(@Param("workId") UUID workId,
            @Param("sourceEpisodeNo") int sourceEpisodeNo);

    @Query("""
            select case when count(distinct analysisJob.id) > 0 then true else false end
            from AnalysisJob analysisJob
            left join analysisJob.targetEpisodes targetEpisode
            where analysisJob.status in :statuses
              and (analysisJob.episode.id = :episodeId or targetEpisode.id = :episodeId)
            """)
    boolean existsActiveByEpisodeTarget(
            @Param("episodeId") UUID episodeId,
            @Param("statuses") Collection<AnalysisJobStatus> statuses
    );

    List<AnalysisJob> findAllBySettingCandidateIdIn(Collection<UUID> settingCandidateIds);

    List<AnalysisJob> findAllByWorldSettingCandidateIdIn(Collection<UUID> worldSettingCandidateIds);

    List<AnalysisJob> findAllByBatchIdAndJobTypeOrderByCreatedAtAsc(
            UUID batchId,
            AnalysisJobType jobType
    );

    @Query("""
            select analysisJob
            from AnalysisJob analysisJob
            left join fetch analysisJob.settingCandidate
            where analysisJob.batch.id = :batchId
              and analysisJob.jobType = :jobType
              and analysisJob.status in :statuses
            order by analysisJob.id asc
            """)
    List<AnalysisJob> findAllActiveComparisonJobs(
            @Param("batchId") UUID batchId,
            @Param("jobType") AnalysisJobType jobType,
            @Param("statuses") Collection<AnalysisJobStatus> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select analysisJob
            from AnalysisJob analysisJob
            where analysisJob.work.id = :workId
              and analysisJob.status in :statuses
            order by analysisJob.createdAt asc
            """)
    List<AnalysisJob> findAllActiveByWorkIdForUpdate(
            @Param("workId") UUID workId,
            @Param("statuses") Collection<AnalysisJobStatus> statuses
    );

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

    @Query("""
            select count(comparisonJob)
            from AnalysisJob comparisonJob
            join comparisonJob.worldSettingCandidate candidate
            where candidate.analysisJob.id = :sourceAnalysisJobId
              and comparisonJob.jobType =
                  org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.WORLD_SETTING_COMPARISON
              and comparisonJob.status in :statuses
            """)
    long countActiveWorldSettingComparisonsBySourceAnalysisJobId(
            @Param("sourceAnalysisJobId") UUID sourceAnalysisJobId,
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

    Optional<AnalysisJob> findFirstByEpisodeIdAndBatchIdAndJobTypeOrderByCreatedAtDesc(
            UUID episodeId,
            UUID batchId,
            AnalysisJobType jobType
    );

    Optional<AnalysisJob> findFirstByEpisodeIdAndBatchIdAndJobTypeOrderByCreatedAtDescIdDesc(
            UUID episodeId,
            UUID batchId,
            AnalysisJobType jobType
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
              and analysisJob.work.lifecycleStatus =
                  org.monitoring.catchholebackend.domain.work.type.WorkLifecycleStatus.ACTIVE
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
    @Query("""
            select count(job) > 0 from AnalysisJob job
            left join job.episode episode
            where job.work.id = :workId
              and job.analysisMode = org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode.ORDERED_PROVISIONAL
              and job.journalStatus <> org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus.INVALIDATED
              and (job.status in (org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING,
                                  org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING)
                   or (((job.status = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.FAILED
                         and job.journalStatus in (org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus.PENDING,
                                                   org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus.INCOMPLETE))
                        or (job.status = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.SUCCEEDED
                            and job.journalStatus = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus.INCOMPLETE))
                       and episode.status <> org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.ARCHIVED
                       and job.sourceEpisodeNo = episode.episodeNo
                       and job.sourceContentHash = episode.contentHash
                       and job.sourceContentS3Key = episode.contentS3Key
                       and (job.sourceContentS3Version = episode.contentS3Version
                            or (job.sourceContentS3Version is null and episode.contentS3Version is null))
                       and not exists (
                           select newer.id from AnalysisJob newer
                           where newer.work.id = job.work.id and newer.episode.id = episode.id
                             and (newer.batch.id = job.batch.id or (newer.batch is null and job.batch is null))
                             and newer.jobType = job.jobType
                             and (newer.createdAt > job.createdAt
                                  or (newer.createdAt = job.createdAt and newer.id > job.id))
                       )))
            """)
    boolean existsUnfinishedOrderedAnalysis(@Param("workId") UUID workId);

}
