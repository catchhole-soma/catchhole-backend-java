package org.monitoring.catchholebackend.domain.character.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettingCandidateRepository extends JpaRepository<SettingCandidate, UUID> {

    @Query("""
            select distinct candidate
            from SettingCandidate candidate
            left join candidate.episode episode
            left join candidate.analysisJob analysisJob
            left join analysisJob.episode jobEpisode
            left join analysisJob.targetEpisodes jobTargetEpisode
            where episode.id = :episodeId
               or (episode is null and (
                    jobEpisode.id = :episodeId
                    or jobTargetEpisode.id = :episodeId
               ))
            """)
    List<SettingCandidate> findAllByAnalysisTargetEpisodeId(@Param("episodeId") UUID episodeId);

    List<SettingCandidate> findAllByWorkIdOrderByCreatedAtDesc(UUID workId);

    List<SettingCandidate> findAllByWorkIdAndReviewStatusOrderByCreatedAtDesc(
            UUID workId,
            SettingCandidateReviewStatus reviewStatus
    );

    List<SettingCandidate> findAllByWorkIdAndEntityNameOrderByCreatedAtDesc(
            UUID workId,
            String entityName
    );

    List<SettingCandidate> findAllByWorkIdAndEntityNameAndReviewStatusOrderByCreatedAtDesc(
            UUID workId,
            String entityName,
            SettingCandidateReviewStatus reviewStatus
    );

    @EntityGraph(attributePaths = {"work", "episode", "analysisJob", "matchedCharacter"})
    @Query(
            value = """
                    select candidate
                    from SettingCandidate candidate
                    join candidate.analysisJob analysisJob
                    left join candidate.episode episode
                    where candidate.work.id = :workId
                      and analysisJob.batch.id = :batchId
                      and (:reviewStatus is null or candidate.reviewStatus = :reviewStatus)
                      and candidate.matchStatus in :matchStatuses
                    order by episode.episodeNo asc, candidate.createdAt asc, candidate.id asc
                    """,
            countQuery = """
                    select count(candidate)
                    from SettingCandidate candidate
                    join candidate.analysisJob analysisJob
                    where candidate.work.id = :workId
                      and analysisJob.batch.id = :batchId
                      and (:reviewStatus is null or candidate.reviewStatus = :reviewStatus)
                      and candidate.matchStatus in :matchStatuses
                    """
    )
    Page<SettingCandidate> findReviewPage(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus,
            @Param("matchStatuses") Collection<SettingCandidateMatchStatus> matchStatuses,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {"work", "episode", "analysisJob", "matchedCharacter"})
    @Query("""
            select candidate
            from SettingCandidate candidate
            join candidate.analysisJob analysisJob
            left join candidate.episode episode
            where candidate.work.id = :workId
              and analysisJob.batch.id = :batchId
              and (:reviewStatus is null or candidate.reviewStatus = :reviewStatus)
              and candidate.matchStatus in :matchStatuses
            order by episode.episodeNo asc, candidate.createdAt asc, candidate.id asc
            """)
    List<SettingCandidate> findReviewCandidates(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus,
            @Param("matchStatuses") Collection<SettingCandidateMatchStatus> matchStatuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"work", "episode", "analysisJob", "matchedCharacter"})
    @Query("""
            select candidate
            from SettingCandidate candidate
            join candidate.analysisJob analysisJob
            left join candidate.episode episode
            where candidate.work.id = :workId
              and analysisJob.batch.id = :batchId
              and candidate.id in :candidateIds
            order by episode.episodeNo asc, candidate.createdAt asc, candidate.id asc
            """)
    List<SettingCandidate> findAllByIdsAndBatchForUpdate(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("candidateIds") Collection<UUID> candidateIds
    );

    @Query("""
            select count(candidate) as totalCandidateCount,
                   coalesce(sum(case
                       when candidate.reviewStatus <> :pendingStatus then 1
                       else 0
                   end), 0) as reviewedCandidateCount,
                   coalesce(sum(case
                       when candidate.reviewStatus = :pendingStatus then 1
                       else 0
                   end), 0) as pendingCandidateCount,
                   coalesce(sum(case
                       when candidate.reviewStatus = :pendingStatus
                        and candidate.matchStatus = :matchRequiredStatus then 1
                       else 0
                   end), 0) as matchRequiredCandidateCount
            from SettingCandidate candidate
            join candidate.analysisJob analysisJob
            where candidate.work.id = :workId
              and analysisJob.batch.id = :batchId
            """)
    SettingCandidateBatchCounts countReviewSummary(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("pendingStatus") SettingCandidateReviewStatus pendingStatus,
            @Param("matchRequiredStatus") SettingCandidateMatchStatus matchRequiredStatus
    );

    @Query("""
            select analysisJob.batch.id as batchId,
                   count(candidate) as totalCandidateCount,
                   coalesce(sum(case
                       when candidate.reviewStatus <> :pendingStatus then 1
                       else 0
                   end), 0) as reviewedCandidateCount,
                   coalesce(sum(case
                       when candidate.reviewStatus = :pendingStatus then 1
                       else 0
                   end), 0) as pendingCandidateCount
            from SettingCandidate candidate
            join candidate.analysisJob analysisJob
            where candidate.work.id = :workId
              and analysisJob.batch.id in :batchIds
            group by analysisJob.batch.id
            """)
    List<SettingCandidateBatchReviewCounts> countReviewSummaryByBatchIds(
            @Param("workId") UUID workId,
            @Param("batchIds") List<UUID> batchIds,
            @Param("pendingStatus") SettingCandidateReviewStatus pendingStatus
    );

    /**
     * 새 분석 작업이 대체할 회차의 미검토 후보만 제거한다.
     * 호출자는 새 Job 저장 전에 실행해 현재 Job의 후보가 함께 지워지지 않도록 해야 한다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            delete from SettingCandidate candidate
            where candidate.work.id = :workId
              and (
                  candidate.episode.id in :episodeIds
                  or candidate.analysisJob.episode.id in :episodeIds
              )
              and candidate.reviewStatus = :reviewStatus
              and candidate.analysisJob.id in (
                  select analysisJob.id
                  from AnalysisJob analysisJob
                  where analysisJob.work.id = :workId
                    and analysisJob.batch.id = :batchId
                    and analysisJob.jobType = :jobType
              )
            """)
    int deleteAllByAnalysisTargetAndReviewStatus(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("episodeIds") Collection<UUID> episodeIds,
            @Param("jobType") AnalysisJobType jobType,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus
    );

    @Query("""
            select candidate
            from SettingCandidate candidate
            where candidate.work.id = :workId
              and (
                  candidate.episode.id in :episodeIds
                  or candidate.analysisJob.episode.id in :episodeIds
              )
              and candidate.reviewStatus = :reviewStatus
              and candidate.analysisJob.batch.id = :batchId
              and candidate.analysisJob.jobType = :jobType
            """)
    List<SettingCandidate> findAllSupersededPendingCandidates(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("episodeIds") Collection<UUID> episodeIds,
            @Param("jobType") AnalysisJobType jobType,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus
    );

    @Query("""
            select candidate
            from SettingCandidate candidate
            where candidate.work.id = :workId
              and candidate.entityType = :entityType
              and candidate.reviewStatus = :reviewStatus
              and candidate.matchStatus = :matchStatus
              and lower(function('regexp_replace', trim(candidate.entityName), '\\s+', ' ', 'g')) = :groupKey
            order by candidate.createdAt desc
            """)
    List<SettingCandidate> findAllByNormalizedEntityNameAndMatchState(
            @Param("workId") UUID workId,
            @Param("groupKey") String groupKey,
            @Param("entityType") SettingEntityType entityType,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus,
            @Param("matchStatus") SettingCandidateMatchStatus matchStatus
    );

    Optional<SettingCandidate> findByIdAndWorkId(UUID id, UUID workId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select candidate from SettingCandidate candidate where candidate.id = :id and candidate.work.id = :workId")
    Optional<SettingCandidate> findByIdAndWorkIdForUpdate(
            @Param("id") UUID id,
            @Param("workId") UUID workId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select candidate
            from SettingCandidate candidate
            join fetch candidate.work
            left join fetch candidate.matchedCharacter
            where candidate.analysisJob.id = :analysisJobId
              and candidate.reviewStatus = :reviewStatus
              and candidate.comparisonStatus = :comparisonStatus
              and not exists (
                  select hiddenJob.id
                  from AnalysisJob hiddenJob
                  where hiddenJob.settingCandidate = candidate
                    and hiddenJob.jobType = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.CHARACTER_FACT_COMPARISON
                    and hiddenJob.status in (
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING,
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING
                    )
              )
            order by candidate.createdAt asc, candidate.id asc
            """)
    List<SettingCandidate> findComparisonClaimCandidates(
            @Param("analysisJobId") UUID analysisJobId,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus,
            @Param("comparisonStatus") CharacterFactComparisonStatus comparisonStatus,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"work", "episode", "analysisJob", "matchedCharacter"})
    @Query("""
            select candidate
            from SettingCandidate candidate
            where candidate.analysisJob.id = :analysisJobId
              and candidate.matchedCharacterId = :characterId
              and candidate.reviewStatus = :reviewStatus
              and candidate.comparisonStatus = :comparisonStatus
              and not exists (
                  select hiddenJob.id
                  from AnalysisJob hiddenJob
                  where hiddenJob.settingCandidate = candidate
                    and hiddenJob.jobType = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.CHARACTER_FACT_COMPARISON
                    and hiddenJob.status in (
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING,
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING
                    )
              )
            order by candidate.createdAt asc, candidate.id asc
            """)
    List<SettingCandidate> findComparisonGroupCandidatesForUpdate(
            @Param("analysisJobId") UUID analysisJobId,
            @Param("characterId") UUID characterId,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus,
            @Param("comparisonStatus") CharacterFactComparisonStatus comparisonStatus
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"work", "episode", "analysisJob", "matchedCharacter", "characterComparisonBatch"})
    @Query("""
            select candidate
            from SettingCandidate candidate
            where candidate.characterComparisonBatch.id = :comparisonBatchId
            order by candidate.createdAt asc, candidate.id asc
            """)
    List<SettingCandidate> findAllByCharacterComparisonBatchIdForUpdate(
            @Param("comparisonBatchId") UUID comparisonBatchId
    );

    @EntityGraph(attributePaths = {"work", "episode", "analysisJob", "analysisJob.batch", "matchedCharacter"})
    @Query("""
            select candidate
            from SettingCandidate candidate
            where candidate.analysisJob.id = :analysisJobId
              and candidate.matchedCharacterId = :characterId
              and candidate.reviewStatus = :reviewStatus
              and candidate.comparisonStatus = :comparisonStatus
              and candidate.characterComparisonBatch is not null
            order by candidate.createdAt asc, candidate.id asc
            """)
    List<SettingCandidate> findCompletedComparisonCandidates(
            @Param("analysisJobId") UUID analysisJobId,
            @Param("characterId") UUID characterId,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus,
            @Param("comparisonStatus") CharacterFactComparisonStatus comparisonStatus
    );

    @EntityGraph(attributePaths = {"episode", "analysisJob", "analysisJob.batch"})
    @Query("""
            select candidate
            from SettingCandidate candidate
            join candidate.analysisJob analysisJob
            left join candidate.episode episode
            where candidate.work.id = :workId
              and analysisJob.batch.id = :batchId
              and candidate.matchedCharacterId = :characterId
              and candidate.reviewStatus = :reviewStatus
              and candidate.attributeName is not null
            order by episode.episodeNo asc, candidate.createdAt asc, candidate.id asc
            """)
    List<SettingCandidate> findPendingComparisonChronology(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("characterId") UUID characterId,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus
    );

    @Query("""
            select candidate
            from SettingCandidate candidate
            where candidate.analysisJob.id = :analysisJobId
              and candidate.comparisonStatus = :comparisonStatus
              and not exists (
                  select hiddenJob.id
                  from AnalysisJob hiddenJob
                  where hiddenJob.settingCandidate = candidate
                    and hiddenJob.jobType = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.CHARACTER_FACT_COMPARISON
                    and hiddenJob.status in (
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING,
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING
                    )
              )
            """)
    List<SettingCandidate> findAllByAnalysisJobIdAndComparisonStatus(
            @Param("analysisJobId") UUID analysisJobId,
            @Param("comparisonStatus") CharacterFactComparisonStatus comparisonStatus
    );

    @Query("""
            select candidate
            from SettingCandidate candidate
            where candidate.analysisJob.id = :analysisJobId
              and candidate.comparisonStatus in :comparisonStatuses
              and not exists (
                  select hiddenJob.id
                  from AnalysisJob hiddenJob
                  where hiddenJob.settingCandidate = candidate
                    and hiddenJob.jobType = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.CHARACTER_FACT_COMPARISON
                    and hiddenJob.status in (
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING,
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING
                    )
              )
            """)
    List<SettingCandidate> findAllByAnalysisJobIdAndComparisonStatusIn(
            @Param("analysisJobId") UUID analysisJobId,
            @Param("comparisonStatuses") Collection<CharacterFactComparisonStatus> comparisonStatuses
    );

    @Query("""
            select case when count(candidate) > 0 then true else false end
            from SettingCandidate candidate
            where candidate.analysisJob.id = :analysisJobId
              and candidate.comparisonStatus in :comparisonStatuses
              and not exists (
                  select hiddenJob.id
                  from AnalysisJob hiddenJob
                  where hiddenJob.settingCandidate = candidate
                    and hiddenJob.jobType = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.CHARACTER_FACT_COMPARISON
                    and hiddenJob.status in (
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING,
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING
                    )
              )
            """)
    boolean existsByAnalysisJobIdAndComparisonStatusIn(
            @Param("analysisJobId") UUID analysisJobId,
            @Param("comparisonStatuses") Collection<CharacterFactComparisonStatus> comparisonStatuses
    );

    Optional<SettingCandidate> findByIdAndWorkIdAndAnalysisJobBatchId(UUID id, UUID workId, UUID batchId);
}
