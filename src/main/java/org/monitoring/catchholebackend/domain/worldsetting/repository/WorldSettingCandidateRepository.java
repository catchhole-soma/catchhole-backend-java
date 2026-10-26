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
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorldSettingCandidateRepository extends JpaRepository<WorldSettingCandidate, UUID> {

    @Query("""
            select distinct candidate.analysisJob from WorldSettingCandidate candidate
            where candidate.work.id = :workId and candidate.id in :candidateIds
              and candidate.reviewStatus = org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.PENDING_REVIEW
            """)
    List<org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob> findPendingReviewSourceJobs(
            @Param("workId") UUID workId, @Param("candidateIds") Collection<UUID> candidateIds);

    boolean existsByAnalysisJobIdAndComparisonStatusAndComparisonFailureCodeIn(UUID analysisJobId,
            org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus comparisonStatus,
            java.util.Collection<org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode> failureCodes);

    @Query("""
            select min(coalesce(episode.episodeNo, 0)) from WorldSettingCandidate candidate
            left join candidate.sourceEpisode episode
            where candidate.work.id = :workId and candidate.id in :candidateIds
              and candidate.reviewStatus = :reviewStatus
            """)
    Integer findMutationSourceEpisodeNo(
            @Param("workId") UUID workId,
            @Param("candidateIds") java.util.Collection<UUID> candidateIds,
            @Param("reviewStatus") WorldSettingReviewStatus reviewStatus
    );

    List<WorldSettingCandidate> findAllBySourceEpisodeId(UUID episodeId);

    Optional<WorldSettingCandidate> findByIdAndWorkId(UUID id, UUID workId);

    Optional<WorldSettingCandidate> findByIdAndWorkIdAndAnalysisJobBatchId(UUID id, UUID workId, UUID batchId);

    @EntityGraph(attributePaths = {
            "work",
            "sourceEpisode",
            "targetWorldSetting",
            "reviewedBy",
            "comparisonBatch",
            "comparisonDecision",
            "comparisonDecision.comparisonBatch"
    })
    @Query("""
            select candidate
            from WorldSettingCandidate candidate
            join candidate.analysisJob analysisJob
            left join candidate.sourceEpisode sourceEpisode
            left join candidate.targetWorldSetting targetWorldSetting
            where candidate.work.id = :workId
              and analysisJob.batch.id = :batchId
              and ((:sourceAnalysisJobId is null and analysisJob.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE)
                or analysisJob.id = :sourceAnalysisJobId)
              and (:reviewStatus is null or candidate.reviewStatus = :reviewStatus)
              and (:category is null
                  or (candidate.finalCategory is not null and candidate.finalCategory = :category)
                  or (candidate.finalCategory is null and targetWorldSetting is not null
                      and targetWorldSetting.category = :category)
                  or (candidate.finalCategory is null and targetWorldSetting is null
                      and candidate.category = :category))
              and (:suggestedOperation is null
                  or (candidate.finalOperation is not null and candidate.finalOperation = :finalOperation)
                  or (candidate.finalOperation is null
                      and candidate.suggestedOperation = :suggestedOperation))
            order by sourceEpisode.episodeNo asc, candidate.createdAt asc, candidate.id asc
            """)
    List<WorldSettingCandidate> findReviewListWithoutJobs(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("reviewStatus") WorldSettingReviewStatus reviewStatus,
            @Param("category") WorldSettingCategory category,
            @Param("suggestedOperation") WorldSettingSuggestedOperation suggestedOperation,
            @Param("finalOperation") WorldSettingOperation finalOperation,
            @Param("sourceAnalysisJobId") UUID sourceAnalysisJobId
    );

    default List<WorldSettingCandidate> findReviewListWithoutJobs(UUID workId, UUID batchId, WorldSettingReviewStatus reviewStatus, WorldSettingCategory category, WorldSettingSuggestedOperation suggestedOperation, WorldSettingOperation finalOperation) {
        return findReviewListWithoutJobs(workId, batchId, reviewStatus, category, suggestedOperation, finalOperation, null);
    }

    @EntityGraph(attributePaths = {
            "work",
            "sourceEpisode",
            "analysisJob",
            "targetWorldSetting",
            "reviewedBy",
            "comparisonDecision",
            "comparisonDecision.comparisonBatch"
    })
    @Query("""
            select candidate
            from WorldSettingCandidate candidate
            join candidate.analysisJob analysisJob
            left join candidate.sourceEpisode sourceEpisode
            left join candidate.targetWorldSetting targetWorldSetting
            where candidate.work.id = :workId
              and analysisJob.batch.id = :batchId
              and ((:sourceAnalysisJobId is null and analysisJob.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE)
                or analysisJob.id = :sourceAnalysisJobId)
              and (:reviewStatus is null or candidate.reviewStatus = :reviewStatus)
              and (:category is null
                  or (candidate.finalCategory is not null and candidate.finalCategory = :category)
                  or (candidate.finalCategory is null and targetWorldSetting is not null
                      and targetWorldSetting.category = :category)
                  or (candidate.finalCategory is null and targetWorldSetting is null
                      and candidate.category = :category))
              and (:suggestedOperation is null
                  or (candidate.finalOperation is not null and candidate.finalOperation = :finalOperation)
                  or (candidate.finalOperation is null
                      and candidate.suggestedOperation = :suggestedOperation))
            order by sourceEpisode.episodeNo asc, candidate.createdAt asc, candidate.id asc
            """)
    List<WorldSettingCandidate> findReviewList(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("reviewStatus") WorldSettingReviewStatus reviewStatus,
            @Param("category") WorldSettingCategory category,
            @Param("suggestedOperation") WorldSettingSuggestedOperation suggestedOperation,
            @Param("finalOperation") WorldSettingOperation finalOperation,
            @Param("sourceAnalysisJobId") UUID sourceAnalysisJobId
    );

    default List<WorldSettingCandidate> findReviewList(UUID workId, UUID batchId, WorldSettingReviewStatus reviewStatus, WorldSettingCategory category, WorldSettingSuggestedOperation suggestedOperation, WorldSettingOperation finalOperation) {
        return findReviewList(workId, batchId, reviewStatus, category, suggestedOperation, finalOperation, null);
    }

    @Query("""
            select analysisJob.id as analysisJobId,
                   count(candidate) as totalCandidateCount,
                   coalesce(sum(case when candidate.reviewStatus <> org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.PENDING_REVIEW then 1 else 0 end), 0) as reviewedCandidateCount,
                   coalesce(sum(case when candidate.reviewStatus = org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.PENDING_REVIEW then 1 else 0 end), 0) as pendingCandidateCount,
                   coalesce(sum(case when candidate.reviewStatus = org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.PENDING_REVIEW
                     and candidate.comparisonStatus not in (org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus.PENDING, org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus.PROCESSING)
                     and not (analysisJob.reviewMode = org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.AUTOMATIC
                         and analysisJob.automaticAppliedAt is null
                         and analysisJob.status in (org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING, org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING))
                     then 1 else 0 end), 0) as directReviewCandidateCount,
                   coalesce(sum(case when candidate.reviewStatus = org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.PENDING_REVIEW
                     and (candidate.comparisonStatus in (org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus.PENDING, org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus.PROCESSING)
                         or (analysisJob.reviewMode = org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.AUTOMATIC
                         and analysisJob.automaticAppliedAt is null
                         and analysisJob.status in (org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING, org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING)))
                     then 1 else 0 end), 0) as processingCandidateCount
            from WorldSettingCandidate candidate join candidate.analysisJob analysisJob
            where candidate.work.id = :workId and analysisJob.work.id = :workId
              and analysisJob.id in :analysisJobIds
              and analysisJob.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.SETTING_BOOK
            group by analysisJob.id
            """)
    List<org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobCandidateReviewCounts> countReviewSummaryByAnalysisJobIds(
            @Param("workId") UUID workId, @Param("analysisJobIds") List<UUID> analysisJobIds);

    @Query("""
            select count(candidate) as totalCandidateCount,
                   coalesce(sum(case when candidate.reviewStatus = org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.CONFIRMED then 1 else 0 end), 0) as confirmedCandidateCount,
                   coalesce(sum(case when candidate.reviewStatus = org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.DISMISSED then 1 else 0 end), 0) as dismissedCandidateCount,
                   coalesce(sum(case when candidate.reviewStatus = :pendingReview
                     and candidate.comparisonStatus not in (org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus.PENDING, org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus.PROCESSING)
                     and not (analysisJob.reviewMode = org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.AUTOMATIC
                         and analysisJob.automaticAppliedAt is null
                         and analysisJob.status in (org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING, org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING))
                     then 1 else 0 end), 0) as directReviewCandidateCount,
                   coalesce(sum(case when candidate.reviewStatus = :pendingReview
                     and (candidate.comparisonStatus in (org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus.PENDING, org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus.PROCESSING)
                         or (analysisJob.reviewMode = org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.AUTOMATIC
                         and analysisJob.automaticAppliedAt is null
                         and analysisJob.status in (org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING, org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING)))
                     then 1 else 0 end), 0) as processingCandidateCount,
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
                       and candidate.comparisonStatus = :failedComparison
                       and candidate.comparisonFailureCode = :quotaFailureCode
                       and (analysisJob.analysisMode = org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode.ORDERED_PROVISIONAL
                           or (analysisJob.reviewMode = org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.AUTOMATIC
                               and analysisJob.automaticAppliedAt is null
                               and analysisJob.status in (org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING, org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING)))
                       then 1 else 0 end), 0) as blockedTokenInterruptedComparisonCount,
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
              and ((:sourceAnalysisJobId is null and analysisJob.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE)
                or analysisJob.id = :sourceAnalysisJobId)
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
            @Param("conflictConsolidation") WorldSettingConsolidationStatus conflictConsolidation,
            @Param("sourceAnalysisJobId") UUID sourceAnalysisJobId
    );

    default WorldSettingCandidateBatchCounts countReviewSummary(UUID workId, UUID batchId, WorldSettingReviewStatus pendingReview, WorldSettingComparisonStatus pendingComparison, WorldSettingComparisonStatus processingComparison, WorldSettingComparisonStatus failedComparison, AnalysisFailureCode quotaFailureCode, WorldSettingComparisonStatus recomparisonRequired, WorldSettingComparisonStatus completedComparison, WorldSettingConsolidationStatus conflictConsolidation) {
        return countReviewSummary(workId, batchId, pendingReview, pendingComparison, processingComparison, failedComparison, quotaFailureCode, recomparisonRequired, completedComparison, conflictConsolidation, null);
    }

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
                       as tokenInterruptedComparisonCount,
                   coalesce(sum(case when candidate.reviewStatus = :pendingReview
                       and candidate.comparisonStatus = :failedComparison
                       and candidate.comparisonFailureCode = :quotaFailureCode
                       and (analysisJob.analysisMode = org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode.ORDERED_PROVISIONAL
                           or (analysisJob.reviewMode = org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.AUTOMATIC
                               and analysisJob.automaticAppliedAt is null
                               and analysisJob.status in (org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING, org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING)))
                       then 1 else 0 end), 0) as blockedTokenInterruptedComparisonCount
            from WorldSettingCandidate candidate
            join candidate.analysisJob analysisJob
            where candidate.work.id = :workId
              and analysisJob.batch.id in :batchIds
              and analysisJob.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE
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
              and ((:sourceAnalysisJobId is null and analysisJob.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE)
                or analysisJob.id = :sourceAnalysisJobId)
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
            @Param("quotaFailureCode") AnalysisFailureCode quotaFailureCode,
            @Param("sourceAnalysisJobId") UUID sourceAnalysisJobId
    );

    default List<WorldSettingCandidate> findTokenInterruptedByBatchForUpdate(UUID workId, UUID batchId, WorldSettingReviewStatus pendingReview, WorldSettingComparisonStatus failedComparison, AnalysisFailureCode quotaFailureCode) {
        return findTokenInterruptedByBatchForUpdate(workId, batchId, pendingReview, failedComparison, quotaFailureCode, null);
    }

    @EntityGraph(attributePaths = {"sourceEpisode", "reviewedBy", "comparisonDecision"})
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
              and ((:sourceAnalysisJobId is null and candidate.analysisJob.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE)
                or candidate.analysisJob.id = :sourceAnalysisJobId)
              and candidate.id in :candidateIds
            order by candidate.id asc
            """)
    List<WorldSettingCandidate> findAllByIdsAndBatchForUpdate(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("candidateIds") Collection<UUID> candidateIds,
            @Param("sourceAnalysisJobId") UUID sourceAnalysisJobId
    );

    default List<WorldSettingCandidate> findAllByIdsAndBatchForUpdate(UUID workId, UUID batchId, Collection<UUID> candidateIds) {
        return findAllByIdsAndBatchForUpdate(workId, batchId, candidateIds, null);
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"sourceEpisode", "analysisJob", "targetWorldSetting", "reviewedBy"})
    @Query("""
            select candidate
            from WorldSettingCandidate candidate
            where candidate.work.id = :workId
              and candidate.analysisJob.batch.id = :batchId
              and ((:sourceAnalysisJobId is null and candidate.analysisJob.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE)
                or candidate.analysisJob.id = :sourceAnalysisJobId)
              and candidate.reviewStatus = :reviewStatus
            order by candidate.id asc
            """)
    List<WorldSettingCandidate> findAllByBatchAndReviewStatusForUpdate(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("reviewStatus") WorldSettingReviewStatus reviewStatus,
            @Param("sourceAnalysisJobId") UUID sourceAnalysisJobId
    );

    default List<WorldSettingCandidate> findAllByBatchAndReviewStatusForUpdate(UUID workId, UUID batchId, WorldSettingReviewStatus reviewStatus) {
        return findAllByBatchAndReviewStatusForUpdate(workId, batchId, reviewStatus, null);
    }

    List<WorldSettingCandidate> findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(UUID analysisJobId);

    List<WorldSettingCandidate> findAllByWorkIdAndReviewStatusOrderByCreatedAtDesc(
            UUID workId, WorldSettingReviewStatus reviewStatus);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"work", "sourceEpisode", "analysisJob"})
    @Query("""
            select candidate
            from WorldSettingCandidate candidate
            where candidate.analysisJob.id = :analysisJobId
              and candidate.reviewStatus = :reviewStatus
              and candidate.comparisonStatus = :comparisonStatus
            order by candidate.createdAt asc, candidate.id asc
            """)
    List<WorldSettingCandidate> findSubjectResolutionCandidatesForUpdate(
            @Param("analysisJobId") UUID analysisJobId,
            @Param("reviewStatus") WorldSettingReviewStatus reviewStatus,
            @Param("comparisonStatus") WorldSettingComparisonStatus comparisonStatus
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"sourceEpisode", "analysisJob"})
    @Query("""
            select candidate
            from WorldSettingCandidate candidate
            where candidate.analysisJob.id = :analysisJobId
              and (candidate.sourceEpisode.id = :sourceEpisodeId
                   or (:sourceEpisodeId is null and candidate.sourceEpisode is null
                       and candidate.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.SETTING_BOOK
                       and candidate.sourceSettingBook = candidate.analysisJob.sourceSettingBook
                       and candidate.sourceSettingBookRevision = candidate.analysisJob.sourceSettingBookRevision))
              and candidate.category = :category
              and candidate.canonicalSubjectKey = :canonicalSubjectKey
              and candidate.reviewStatus = :reviewStatus
              and candidate.comparisonStatus = :comparisonStatus
            order by candidate.createdAt asc, candidate.id asc
            """)
    List<WorldSettingCandidate> findComparisonBatchCandidatesForUpdate(
            @Param("analysisJobId") UUID analysisJobId,
            @Param("sourceEpisodeId") UUID sourceEpisodeId,
            @Param("category") WorldSettingCategory category,
            @Param("canonicalSubjectKey") String canonicalSubjectKey,
            @Param("reviewStatus") WorldSettingReviewStatus reviewStatus,
            @Param("comparisonStatus") WorldSettingComparisonStatus comparisonStatus
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {
            "sourceEpisode",
            "analysisJob",
            "comparisonBatch",
            "comparisonDecision",
            "comparisonDecision.targetWorldSetting"
    })
    List<WorldSettingCandidate> findAllByComparisonBatchIdOrderByCreatedAtAscIdAsc(
            UUID comparisonBatchId
    );

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
