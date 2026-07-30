package org.monitoring.catchholebackend.domain.character.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettingCandidateRepository extends JpaRepository<SettingCandidate, UUID> {

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

    @EntityGraph(attributePaths = {"work", "episode", "analysisJob"})
    @Query(
            value = """
                    select candidate
                    from SettingCandidate candidate
                    join candidate.analysisJob analysisJob
                    left join candidate.episode episode
                    where candidate.work.id = :workId
                      and analysisJob.batch.id = :batchId
                      and (:reviewStatus is null or candidate.reviewStatus = :reviewStatus)
                      and (:matchStatus is null or candidate.matchStatus = :matchStatus)
                    order by episode.episodeNo asc, candidate.createdAt asc, candidate.id asc
                    """,
            countQuery = """
                    select count(candidate)
                    from SettingCandidate candidate
                    join candidate.analysisJob analysisJob
                    where candidate.work.id = :workId
                      and analysisJob.batch.id = :batchId
                      and (:reviewStatus is null or candidate.reviewStatus = :reviewStatus)
                      and (:matchStatus is null or candidate.matchStatus = :matchStatus)
                    """
    )
    Page<SettingCandidate> findReviewPage(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus,
            @Param("matchStatus") SettingCandidateMatchStatus matchStatus,
            Pageable pageable
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
              and candidate.entityType = :entityType
              and candidate.reviewStatus = :reviewStatus
              and candidate.matchStatus = :matchStatus
              and trim(candidate.entityName) = :entityName
            order by candidate.createdAt desc
            """)
    List<SettingCandidate> findAllByNormalizedEntityNameAndMatchState(
            @Param("workId") UUID workId,
            @Param("entityName") String entityName,
            @Param("entityType") SettingEntityType entityType,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus,
            @Param("matchStatus") SettingCandidateMatchStatus matchStatus
    );

    Optional<SettingCandidate> findByIdAndWorkId(UUID id, UUID workId);

    Optional<SettingCandidate> findByIdAndWorkIdAndAnalysisJobBatchId(UUID id, UUID workId, UUID batchId);
}
