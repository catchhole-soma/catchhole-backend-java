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
    @Query("select job.work.id from AnalysisJob job where job.id = :id")
    Optional<UUID> findWorkIdById(@Param("id") UUID id);

    @Query("""
            select new org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobProgressRow(
                job.id, job.episode.id, job.jobType, job.status, job.reviewMode, job.automaticAppliedAt,
                job.failureCode, job.analysisMode, job.analysisRunId, job.runGeneration, job.runSequence,
                job.predecessorJobId, job.journalStatus, job.journalInvalidationReason, job.checkpointStage)
            from AnalysisJob job
            where job.work.id = :workId and job.id in :analysisJobIds
            """)
    List<AnalysisJobProgressRow> findProgressByWorkIdAndIdIn(
            @Param("workId") UUID workId, @Param("analysisJobIds") Collection<UUID> analysisJobIds);

    @Query("""
            select new org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobProgressEpisodeRow(
                job.id, coalesce(target.id, episode.id),
                case when target.id is not null then target.episodeNo else episode.episodeNo end,
                case when target.id is not null then target.title else episode.title end,
                case when target.id is not null then target.status else episode.status end,
                case when target.id is not null then target.updatedAt else episode.updatedAt end)
            from AnalysisJob job
            left join job.targetEpisodes target
            left join job.episode episode
            where job.work.id = :workId and job.id in :analysisJobIds
              and ((target.id is not null and target.work.id = :workId)
                or (target.id is null and episode.work.id = :workId))
            order by job.id, coalesce(target.episodeNo, episode.episodeNo), coalesce(target.id, episode.id)
            """)
    List<AnalysisJobProgressEpisodeRow> findProgressEpisodesByWorkIdAndJobIdIn(
            @Param("workId") UUID workId, @Param("analysisJobIds") Collection<UUID> analysisJobIds);

    List<AnalysisJob> findAllByWorkIdAndSourceSettingBookIdOrderByCreatedAtDescIdDesc(UUID workId, UUID sourceSettingBookId);
    List<AnalysisJob> findAllByWorkIdAndSourceKindAndJobTypeOrderByCreatedAtDescIdDesc(UUID workId,
            org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind sourceKind, AnalysisJobType jobType);
    Optional<AnalysisJob> findFirstBySourceSettingBookIdAndJobTypeOrderByCreatedAtDescIdDesc(UUID bookId, AnalysisJobType jobType);


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

    boolean existsByBatchIdAndJobTypeAndCharacterComparisonInputHashAndStatusIn(
            UUID batchId,
            AnalysisJobType jobType,
            String characterComparisonInputHash,
            Collection<AnalysisJobStatus> statuses
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

    /**
     * GH312: 파일 교체 뒤 재검수 report는 회차의 새 업로드 batch에 묶이므로 퇴역 report만 남은 옛 batch는 목록에 올리지 않는다.
     * 표시되는 batch의 최초·최근 요청 시각은 계속 모든 접수 이력을 집계한다.
     */
    @Query(value = """
            with requests as (
                select batch_id, created_at from analysis_jobs where work_id = :workId and batch_id is not null
                  and job_type not in ('WORLD_SETTING_COMPARISON','CHARACTER_FACT_COMPARISON')
                union all select batch_id, created_at from validation_reports where work_id = :workId
            ) select cast(b.id as varchar) as batchId, b.upload_type as uploadType, min(r.created_at) as firstRequestedAt,
                max(r.created_at) as lastRequestedAt from upload_batches b join requests r on r.batch_id = b.id
                where exists (select 1 from analysis_jobs j where j.batch_id = b.id and j.job_type not in ('WORLD_SETTING_COMPARISON','CHARACTER_FACT_COMPARISON'))
                   or exists (select 1 from validation_reports v where v.batch_id = b.id and v.is_current = true)
                group by b.id, b.upload_type order by max(r.created_at) desc, b.id desc
            """, countQuery = """
            select count(*) from upload_batches b where b.work_id = :workId and (
                exists (select 1 from analysis_jobs j where j.batch_id = b.id and j.job_type not in ('WORLD_SETTING_COMPARISON','CHARACTER_FACT_COMPARISON'))
                or exists (select 1 from validation_reports r where r.batch_id = b.id and r.is_current = true))
            """, nativeQuery = true)
    Page<AnalysisBatchPageRow> findBatchPage(
            @Param("workId") UUID workId,
            Pageable pageable
    );

    @Query("""
            select analysisJob.batch.id as batchId,
                   count(analysisJob) as activeComparisonCount
            from AnalysisJob analysisJob
            where analysisJob.work.id = :workId
              and analysisJob.batch.id in :batchIds
              and analysisJob.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE
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

    @Query("select count(job) > 0 from AnalysisJob job where job.batch.id = :batchId and job.episode is null and job.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE and job.status in :statuses")
    boolean existsByBatchIdAndEpisodeIsNullAndStatusIn(@Param("batchId") UUID batchId, @Param("statuses") Collection<AnalysisJobStatus> statuses);

    @Query("select count(job) > 0 from AnalysisJob job where job.batch.id = :batchId and job.episode is null and job.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE and job.status in :statuses and job.jobType not in :excludedJobTypes")
    boolean existsByBatchIdAndEpisodeIsNullAndJobTypeNotInAndStatusIn(@Param("batchId") UUID batchId, @Param("excludedJobTypes") Collection<AnalysisJobType> excludedJobTypes, @Param("statuses") Collection<AnalysisJobStatus> statuses);

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

    /**
     * GH312: 회차의 현재 검수 report에 검토 종료 receipt가 없으면 true다. claim의 {@link AnalysisJobEligibility#DOMAIN},
     * 늦은 lease callback, 후보 반영 출처 검사와 같은 규칙이며 퇴역 report와 receipt는 판단에 쓰지 않는다.
     */
    @Query("""
            select count(r) > 0 from ValidationReport r where r.episode.id in :episodeIds and r.current = true
              and not exists (select c.id from ValidationReviewCompletion c where c.report.id = r.id)
            """)
    boolean existsValidationStagingForEpisodes(@Param("episodeIds") java.util.Collection<UUID> episodeIds);

    /**
     * GH312 S3: 회차의 현재 검수 report에 검토 종료 receipt가 있으면(release 회차) true다. 이 회차의 확정 설정은
     * 회차 전체 확정만 쓰므로 사람의 개별·그룹 확정·승격 경로를 막는 데 사용한다.
     */
    @Query("""
            select count(r) > 0 from ValidationReport r where r.episode.id in :episodeIds and r.current = true
              and exists (select c.id from ValidationReviewCompletion c where c.report.id = r.id)
            """)
    boolean existsReleasedValidationForEpisodes(@Param("episodeIds") java.util.Collection<UUID> episodeIds);

    /** GH312: 공개 생성 차단과 release Job 재시도 판정용. 검토 종료 여부와 무관하게 현재 검수 report가 있으면 true다. */
    @Query("select count(r) > 0 from ValidationReport r where r.episode.id in :episodeIds and r.current = true")
    boolean existsCurrentValidationReportForEpisodes(@Param("episodeIds") java.util.Collection<UUID> episodeIds);

}
