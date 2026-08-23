package org.monitoring.catchholebackend.domain.worldsetting.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingConsolidationStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorldSettingCandidateRepository extends JpaRepository<WorldSettingCandidate, UUID> {

    List<WorldSettingCandidate> findAllBySourceEpisodeId(UUID episodeId);

    Optional<WorldSettingCandidate> findByIdAndWorkId(UUID id, UUID workId);

    Optional<WorldSettingCandidate> findByIdAndWorkIdAndAnalysisJobBatchId(UUID id, UUID workId, UUID batchId);

    @EntityGraph(attributePaths = {"work", "sourceEpisode", "analysisJob", "targetWorldSetting", "reviewedBy"})
    @Query("""
            select candidate
            from WorldSettingCandidate candidate
            join candidate.analysisJob analysisJob
            join candidate.sourceEpisode sourceEpisode
            left join candidate.targetWorldSetting targetWorldSetting
            where candidate.work.id = :workId
              and analysisJob.batch.id = :batchId
              and (:reviewStatus is null or candidate.reviewStatus = :reviewStatus)
              and (:category is null
                  or (candidate.finalCategory is not null and candidate.finalCategory = :category)
                  or (candidate.finalCategory is null and targetWorldSetting is not null
                      and targetWorldSetting.category = :category)
                  or (candidate.finalCategory is null and targetWorldSetting is null
                      and candidate.category = :category))
              and (:operation is null
                  or (candidate.finalOperation is not null and candidate.finalOperation = :operation)
                  or (candidate.finalOperation is null and candidate.suggestedOperation = :operation))
            order by sourceEpisode.episodeNo asc, candidate.createdAt asc, candidate.id asc
            """)
    List<WorldSettingCandidate> findReviewList(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("reviewStatus") WorldSettingReviewStatus reviewStatus,
            @Param("category") WorldSettingCategory category,
            @Param("operation") WorldSettingOperation operation
    );

    @Query("""
            select count(candidate) as totalCandidateCount,
                   coalesce(sum(case when candidate.reviewStatus <> :pendingReview then 1 else 0 end), 0)
                       as reviewedCandidateCount,
                   coalesce(sum(case when candidate.reviewStatus = :pendingReview then 1 else 0 end), 0)
                       as pendingCandidateCount,
                   coalesce(sum(case when candidate.reviewStatus = :pendingReview
                       and candidate.comparisonStatus = :pendingComparison then 1 else 0 end), 0)
                       as pendingComparisonCount,
                   coalesce(sum(case when candidate.reviewStatus = :pendingReview
                       and candidate.comparisonStatus = :processingComparison then 1 else 0 end), 0)
                       as processingComparisonCount,
                   coalesce(sum(case when candidate.reviewStatus = :pendingReview
                       and candidate.comparisonStatus = :failedComparison then 1 else 0 end), 0)
                       as failedComparisonCount,
                   coalesce(sum(case when candidate.reviewStatus = :pendingReview
                       and candidate.comparisonStatus = :failedComparison
                       and candidate.comparisonFailureCode = :quotaFailureCode then 1 else 0 end), 0)
                       as tokenInterruptedComparisonCount,
                   coalesce(sum(case when candidate.reviewStatus = :pendingReview
                       and candidate.comparisonStatus = :recomparisonRequired then 1 else 0 end), 0)
                       as recomparisonRequiredCount,
                   coalesce(sum(case when candidate.reviewStatus = :pendingReview
                       and candidate.comparisonStatus = :completedComparison
                       and candidate.consolidationStatus = :conflictConsolidation then 1 else 0 end), 0)
                       as conflictCandidateCount
            from WorldSettingCandidate candidate
            join candidate.analysisJob analysisJob
            where candidate.work.id = :workId
              and analysisJob.batch.id = :batchId
            """)
    WorldSettingCandidateBatchCounts countReviewSummary(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("pendingReview") WorldSettingReviewStatus pendingReview,
            @Param("pendingComparison") WorldSettingComparisonStatus pendingComparison,
            @Param("processingComparison") WorldSettingComparisonStatus processingComparison,
            @Param("failedComparison") WorldSettingComparisonStatus failedComparison,
            @Param("quotaFailureCode") AnalysisFailureCode quotaFailureCode,
            @Param("recomparisonRequired") WorldSettingComparisonStatus recomparisonRequired,
            @Param("completedComparison") WorldSettingComparisonStatus completedComparison,
            @Param("conflictConsolidation") WorldSettingConsolidationStatus conflictConsolidation
    );

    @Query("""
            select analysisJob.batch.id as batchId,
                   count(candidate) as totalCandidateCount,
                   coalesce(sum(case when candidate.reviewStatus <> :pendingReview then 1 else 0 end), 0)
                       as reviewedCandidateCount,
                   coalesce(sum(case when candidate.reviewStatus = :pendingReview then 1 else 0 end), 0)
                       as pendingCandidateCount,
                   coalesce(sum(case when candidate.reviewStatus = :pendingReview
                       and candidate.comparisonStatus = :pendingComparison then 1 else 0 end), 0)
                       as pendingComparisonCount,
                   coalesce(sum(case when candidate.reviewStatus = :pendingReview
                       and candidate.comparisonStatus = :processingComparison then 1 else 0 end), 0)
                       as processingComparisonCount,
                   coalesce(sum(case when candidate.reviewStatus = :pendingReview
                       and candidate.comparisonStatus = :failedComparison
                       and candidate.comparisonFailureCode = :quotaFailureCode then 1 else 0 end), 0)
                       as tokenInterruptedComparisonCount
            from WorldSettingCandidate candidate
            join candidate.analysisJob analysisJob
            where candidate.work.id = :workId
              and analysisJob.batch.id in :batchIds
            group by analysisJob.batch.id
            """)
    List<WorldSettingCandidateBatchReviewCounts> countReviewSummaryByBatchIds(
            @Param("workId") UUID workId,
            @Param("batchIds") List<UUID> batchIds,
            @Param("pendingReview") WorldSettingReviewStatus pendingReview,
            @Param("pendingComparison") WorldSettingComparisonStatus pendingComparison,
            @Param("processingComparison") WorldSettingComparisonStatus processingComparison,
            @Param("failedComparison") WorldSettingComparisonStatus failedComparison,
            @Param("quotaFailureCode") AnalysisFailureCode quotaFailureCode
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"sourceEpisode", "analysisJob"})
    @Query("""
            select candidate
            from WorldSettingCandidate candidate
            join candidate.analysisJob analysisJob
            where candidate.work.id = :workId
              and analysisJob.batch.id = :batchId
              and candidate.reviewStatus = :pendingReview
              and candidate.comparisonStatus = :failedComparison
              and candidate.comparisonFailureCode = :quotaFailureCode
            order by candidate.createdAt asc, candidate.id asc
            """)
    List<WorldSettingCandidate> findTokenInterruptedByBatchForUpdate(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("pendingReview") WorldSettingReviewStatus pendingReview,
            @Param("failedComparison") WorldSettingComparisonStatus failedComparison,
            @Param("quotaFailureCode") AnalysisFailureCode quotaFailureCode
    );

    @EntityGraph(attributePaths = {"sourceEpisode", "reviewedBy"})
    List<WorldSettingCandidate> findAllByTargetWorldSettingIdAndReviewStatusOrderByReviewedAtDescCreatedAtDescIdDesc(
            UUID targetWorldSettingId,
            WorldSettingReviewStatus reviewStatus
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select candidate
            from WorldSettingCandidate candidate
            where candidate.id = :id
              and candidate.work.id = :workId
            """)
    Optional<WorldSettingCandidate> findByIdAndWorkIdForUpdate(
            @Param("id") UUID id,
            @Param("workId") UUID workId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"sourceEpisode", "analysisJob", "targetWorldSetting", "reviewedBy"})
    @Query("""
            select candidate
            from WorldSettingCandidate candidate
            where candidate.work.id = :workId
              and candidate.analysisJob.batch.id = :batchId
              and candidate.id in :candidateIds
            order by candidate.id asc
            """)
    List<WorldSettingCandidate> findAllByIdsAndBatchForUpdate(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("candidateIds") Collection<UUID> candidateIds
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"sourceEpisode", "analysisJob", "targetWorldSetting", "reviewedBy"})
    @Query("""
            select candidate
            from WorldSettingCandidate candidate
            where candidate.work.id = :workId
              and candidate.analysisJob.batch.id = :batchId
              and candidate.reviewStatus = :reviewStatus
            order by candidate.id asc
            """)
    List<WorldSettingCandidate> findAllByBatchAndReviewStatusForUpdate(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("reviewStatus") WorldSettingReviewStatus reviewStatus
    );

    List<WorldSettingCandidate> findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(UUID analysisJobId);

    boolean existsByAnalysisJobIdAndReviewStatusNot(
            UUID analysisJobId,
            WorldSettingReviewStatus reviewStatus
    );

    @Modifying(flushAutomatically = true)
    void deleteAllByAnalysisJobId(UUID analysisJobId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select candidate
            from WorldSettingCandidate candidate
            where candidate.analysisJob.id = :analysisJobId
              and candidate.reviewStatus = :reviewStatus
              and candidate.comparisonStatus = :comparisonStatus
            order by candidate.createdAt asc, candidate.id asc
            """)
    List<WorldSettingCandidate> findComparisonClaimCandidates(
            @Param("analysisJobId") UUID analysisJobId,
            @Param("reviewStatus") WorldSettingReviewStatus reviewStatus,
            @Param("comparisonStatus") WorldSettingComparisonStatus comparisonStatus,
            Pageable pageable
    );

    List<WorldSettingCandidate> findAllByAnalysisJobIdAndComparisonStatus(
            UUID analysisJobId,
            WorldSettingComparisonStatus comparisonStatus
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<WorldSettingCandidate> findAllByAnalysisJobIdAndReviewStatusAndComparisonStatusIn(
            UUID analysisJobId,
            WorldSettingReviewStatus reviewStatus,
            Collection<WorldSettingComparisonStatus> comparisonStatuses
    );

    boolean existsByAnalysisJobIdAndComparisonStatusIn(
            UUID analysisJobId,
            Collection<WorldSettingComparisonStatus> comparisonStatuses
    );

    boolean existsByAnalysisJobIdAndReviewStatusAndComparisonStatusAndComparisonFailureCode(
            UUID analysisJobId,
            WorldSettingReviewStatus reviewStatus,
            WorldSettingComparisonStatus comparisonStatus,
            AnalysisFailureCode comparisonFailureCode
    );

    @Query("""
            select candidate
            from WorldSettingCandidate candidate
            where candidate.work.id = :workId
              and candidate.sourceEpisode.id in :episodeIds
              and candidate.reviewStatus = :reviewStatus
              and candidate.analysisJob.batch.id = :batchId
              and candidate.analysisJob.jobType = :jobType
            """)
    List<WorldSettingCandidate> findAllSupersededPendingCandidates(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("episodeIds") Collection<UUID> episodeIds,
            @Param("jobType") AnalysisJobType jobType,
            @Param("reviewStatus") WorldSettingReviewStatus reviewStatus
    );
}
