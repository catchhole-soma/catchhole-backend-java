package org.monitoring.catchholebackend.domain.analysis.repository;

import jakarta.persistence.LockModeType;
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
    @Query("""
            select count(job.id) from AnalysisJob job join job.episode episode
            where job.work.id = :workId and episode.episodeNo < :beforeEpisodeNo
              and episode.status <> org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.ARCHIVED
              and job.sourceReferenceInvalidatedAt is null
              and job.jobType = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION
              and job.status in (org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING,
                                 org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING)
            """)
    long countActiveEarlierExtractions(@Param("workId") UUID workId, @Param("beforeEpisodeNo") int beforeEpisodeNo);

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

    // 화면이 "분석 완료"로 보는 기준과 같다: 단일 분석은 성공, 순차 분석은 봉인과 자동 반영(또는 검수 보류)까지 끝난
    // 설정 추출(회차·설정집). 조건을 바꾸면 AnalysisJob.isCompletedOrderedAnalysis와 V123 백필도 함께 맞춘다.
    @Query("""
            select count(job) > 0 from AnalysisJob job
            where job.work.member.id = :memberId
              and job.jobType = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION
              and job.status = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.SUCCEEDED
              and (job.analysisMode <> org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode.ORDERED_PROVISIONAL
                   or (job.journalStatus = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus.SEALED
                       and (job.reviewMode <> org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.AUTOMATIC
                            or job.automaticAppliedAt is not null
                            or job.reviewApplicationHeldAt is not null)))
            """)
    boolean existsCompletedSettingExtractionByMemberId(@Param("memberId") Long memberId);

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

    /*
     * #387 자동 누적 생성의 미래 출처 검사. 위 네 조회는 지금의 회차 번호를 보고, 아래 세 조회는 추출 Job에 고정한
     * source_episode_no를 본다(회차 이동 뒤 둘이 다르다). 설정집 Job은 NULL이라 max에서 빠진다.
     */
    @Query("""
            select max(job.sourceEpisodeNo) from CharacterSnapshotSource source
            join source.workCharacter owner join source.sourceFact fact join fact.extractedByJob job
            where owner.work.id = :workId
              and owner.status = org.monitoring.catchholebackend.domain.character.type.CharacterStatus.ACTIVE
            """)
    Integer findLatestCurrentCharacterSourceJobEpisodeNo(@Param("workId") UUID workId);

    @Query("""
            select max(job.sourceEpisodeNo) from SettingCandidate candidate join candidate.analysisJob job
            where candidate.work.id = :workId
              and candidate.candidateKind = org.monitoring.catchholebackend.domain.character.type.SettingCandidateKind.CHARACTER_DISCOVERY
              and candidate.reviewStatus = org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.CONFIRMED
              and exists (select owner.id from WorkCharacter owner
                          where owner.id = candidate.matchedCharacterId and owner.work.id = :workId
                            and owner.status = org.monitoring.catchholebackend.domain.character.type.CharacterStatus.ACTIVE)
            """)
    Integer findLatestConfirmedDiscoveryJobEpisodeNo(@Param("workId") UUID workId);

    @Query("""
            select max(job.sourceEpisodeNo) from WorldSettingCandidate candidate
            join candidate.targetWorldSetting setting join candidate.analysisJob job
            where setting.work.id = :workId
              and candidate.reviewStatus = org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.CONFIRMED
              and candidate.historyOnly = false
            """)
    Integer findLatestConfirmedWorldSourceJobEpisodeNo(@Param("workId") UUID workId);

    @Query("select candidate.id from SettingCandidate candidate where candidate.analysisJob.id = :jobId")
    List<UUID> findCharacterSourceCandidateIds(@Param("jobId") UUID jobId);

    @Query("select candidate.id from WorldSettingCandidate candidate where candidate.analysisJob.id = :jobId")
    List<UUID> findWorldSourceCandidateIds(@Param("jobId") UUID jobId);

    List<AnalysisJob> findAllByAnalysisRunIdAndRunGenerationOrderByRunSequenceAsc(UUID runId, Long generation);

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
              and analysisJob.jobType in (
                  org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION,
                  org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.EPISODE_VALIDATION
              )
            order by analysisJob.createdAt desc
            """)
    List<AnalysisJob> findAllWithTargetsByWorkIdOrderByCreatedAtDesc(@Param("workId") UUID workId);

    /** 분석 기록을 지운 batch는 지운 시각 뒤의 새 분석 요청·검수 접수가 있을 때만 목록에 다시 나온다(alias b = upload_batches). */
    String VISIBLE_ANALYSIS_HISTORY = """
            (b.analysis_history_deleted_at is null
               or exists (select 1 from analysis_jobs dj where dj.batch_id = b.id
                   and dj.job_type in ('SETTING_EXTRACTION','EPISODE_VALIDATION') and dj.created_at > b.analysis_history_deleted_at)
               or exists (select 1 from validation_reports dv where dv.batch_id = b.id and dv.created_at > b.analysis_history_deleted_at))
            """;

    /**
     * GH312: 파일 교체 뒤 재검수 report는 회차의 새 업로드 batch에 묶이므로 퇴역 report만 남은 옛 batch는 목록에 올리지 않는다.
     * 표시되는 batch의 최초·최근 요청 시각은 계속 모든 접수 이력을 집계한다.
     * kind는 {@link org.monitoring.catchholebackend.domain.analysis.type.AnalysisBatchKind} 이름이며,
     * ANALYSIS는 분석 Job이 있는 batch, VALIDATION은 현재 검수 report가 있는 batch라서 검토를 마쳐 설정 추출이
     * 시작된 검수 batch는 두 목록에 모두 나온다.
     * 작가가 분석 기록을 지운 batch는 지운 시각 뒤에 새 분석 요청이나 검수 접수가 생길 때까지 모든 kind에서 뺀다.
     */
    @Query(value = """
            with requests as (
                select batch_id, created_at from analysis_jobs where work_id = :workId and batch_id is not null
                  and job_type in ('SETTING_EXTRACTION','EPISODE_VALIDATION')
                union all select batch_id, created_at from validation_reports where work_id = :workId
            ) select cast(b.id as varchar) as batchId, b.upload_type as uploadType, min(r.created_at) as firstRequestedAt,
                max(r.created_at) as lastRequestedAt from upload_batches b join requests r on r.batch_id = b.id
                where (exists (select 1 from analysis_jobs j where j.batch_id = b.id and j.job_type in ('SETTING_EXTRACTION','EPISODE_VALIDATION'))
                   or exists (select 1 from validation_reports v where v.batch_id = b.id and v.is_current = true))
                  and (:kind = 'ALL'
                   or (:kind = 'VALIDATION' and exists (select 1 from validation_reports v where v.batch_id = b.id and v.is_current = true))
                   or (:kind = 'ANALYSIS' and exists (select 1 from analysis_jobs j where j.batch_id = b.id and j.job_type in ('SETTING_EXTRACTION','EPISODE_VALIDATION'))))
                  and """ + VISIBLE_ANALYSIS_HISTORY + """
                group by b.id, b.upload_type order by max(r.created_at) desc, b.id desc
            """, countQuery = """
            select count(*) from upload_batches b where b.work_id = :workId and (
                exists (select 1 from analysis_jobs j where j.batch_id = b.id and j.job_type in ('SETTING_EXTRACTION','EPISODE_VALIDATION'))
                or exists (select 1 from validation_reports r where r.batch_id = b.id and r.is_current = true))
              and (:kind = 'ALL'
                or (:kind = 'VALIDATION' and exists (select 1 from validation_reports r where r.batch_id = b.id and r.is_current = true))
                or (:kind = 'ANALYSIS' and exists (select 1 from analysis_jobs j where j.batch_id = b.id and j.job_type in ('SETTING_EXTRACTION','EPISODE_VALIDATION'))))
              and """ + VISIBLE_ANALYSIS_HISTORY + """
            """, nativeQuery = true)
    Page<AnalysisBatchPageRow> findBatchPage(
            @Param("workId") UUID workId,
            @Param("kind") String kind,
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
    /** GH201 진행·대기·같은 입력으로 재개할 순차 분석. 확정 설정 변경 거절과 회차 전체 확정 안내(D2, #387)가 같은 조건을 쓴다. */
    String UNFINISHED_ORDERED_ANALYSIS_FROM = """
            from AnalysisJob job
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
            """;
    String UNFINISHED_ORDERED_ANALYSIS = "select count(job) > 0 " + UNFINISHED_ORDERED_ANALYSIS_FROM;

    @Query(UNFINISHED_ORDERED_ANALYSIS)
    boolean existsUnfinishedOrderedAnalysis(@Param("workId") UUID workId);

    /** 분석 기록 삭제 가능 여부용. 재개해야 할 순차 분석을 가진 batch를 숨기면 재개 안내(분석 목록)로 돌아갈 길이 없어진다. */
    @Query("select distinct job.batch.id " + UNFINISHED_ORDERED_ANALYSIS_FROM + "  and job.batch.id in :batchIds")
    List<UUID> findBatchIdsWithUnfinishedOrderedAnalysis(
            @Param("workId") UUID workId,
            @Param("batchIds") Collection<UUID> batchIds
    );

    /** 분석 기록 삭제 가능 여부용. 내부 비교 Job까지 모든 Job type의 대기·실행을 본다. */
    @Query("""
            select distinct job.batch.id from AnalysisJob job
            where job.work.id = :workId
              and job.batch.id in :batchIds
              and job.status in (org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING,
                                 org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING)
            """)
    List<UUID> findBatchIdsWithActiveJobs(
            @Param("workId") UUID workId,
            @Param("batchIds") Collection<UUID> batchIds
    );

    /**
     * D2(#387) 검수 회차의 APPLY_SETTINGS 안내용. 그 report의 release Job 자신은 빼고 묻는다. release Job이 추출 중이거나 실패했을 때의
     * 명시 반영은 지금처럼 NOT_READY·보류로 처리되고, 회차 전체 확정 READY는 release Job이 SUCCEEDED·SEALED라 위 조건에 걸리지 않는다.
     */
    @Query(UNFINISHED_ORDERED_ANALYSIS + "  and job.id <> :excludedJobId")
    boolean existsUnfinishedOrderedAnalysisOtherThan(@Param("workId") UUID workId, @Param("excludedJobId") UUID excludedJobId);

    /**
     * 확정 전 회차의 현재 검수 report에 검토 종료 receipt가 없으면 true다. claim의 {@link AnalysisJobEligibility#DOMAIN},
     * 늦은 lease callback, 후보 반영 출처 검사와 같은 규칙이며 퇴역 report와 receipt는 판단에 쓰지 않는다.
     * DIAGNOSTIC_ONLY는 검토 종료가 없으므로 이 조건에서 제외하고 실행 중 쓰기 잠금은 ValidationSettingsLock이 맡는다.
     */
    @Query("""
            select count(r) > 0 from ValidationReport r where r.episode.id in :episodeIds and r.current = true
              and r.mode = org.monitoring.catchholebackend.domain.validation.type.ValidationReportMode.BEFORE_CONFIRMATION
              and not exists (select c.id from ValidationReviewCompletion c where c.report.id = r.id)
            """)
    boolean existsValidationStagingForEpisodes(@Param("episodeIds") java.util.Collection<UUID> episodeIds);

    /**
     * 검토 종료 뒤 아직 반영 완료(APPLIED)가 아닌 회차만 사람의 확정을 막는다.
     * 자동 반영이 끝난 회차는 남은 후보를 일반 추출과 같은 개별·그룹 확정으로 처리할 수 있다.
     */
    @Query("""
            select count(r) > 0 from ValidationReport r where r.episode.id in :episodeIds and r.current = true
              and exists (select c.id from ValidationReviewCompletion c where c.report.id = r.id)
              and not exists (select a.id from ValidationSettingsApplication a where a.report.id = r.id
                and a.status = org.monitoring.catchholebackend.domain.validation.type.ValidationApplicationStatus.APPLIED)
            """)
    boolean existsReleasedValidationForEpisodes(@Param("episodeIds") java.util.Collection<UUID> episodeIds);

    /** GH312: 공개 생성 차단과 release Job 재시도 판정용. 검토 종료 여부와 무관하게 현재 검수 report가 있으면 true다. */
    @Query("select count(r) > 0 from ValidationReport r where r.episode.id in :episodeIds and r.current = true")
    boolean existsCurrentValidationReportForEpisodes(@Param("episodeIds") java.util.Collection<UUID> episodeIds);

}
