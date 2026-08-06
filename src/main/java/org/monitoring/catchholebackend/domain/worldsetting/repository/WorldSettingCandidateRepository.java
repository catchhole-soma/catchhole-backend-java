package org.monitoring.catchholebackend.domain.worldsetting.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorldSettingCandidateRepository extends JpaRepository<WorldSettingCandidate, UUID> {

    Optional<WorldSettingCandidate> findByIdAndWorkId(UUID id, UUID workId);

    Optional<WorldSettingCandidate> findByIdAndWorkIdAndAnalysisJobBatchId(UUID id, UUID workId, UUID batchId);

    @EntityGraph(attributePaths = {"work", "sourceEpisode", "analysisJob", "targetWorldSetting", "reviewedBy"})
    @Query(
            value = """
                    select candidate
                    from WorldSettingCandidate candidate
                    join candidate.analysisJob analysisJob
                    join candidate.sourceEpisode sourceEpisode
                    where candidate.work.id = :workId
                      and analysisJob.batch.id = :batchId
                      and (:reviewStatus is null or candidate.reviewStatus = :reviewStatus)
                      and (:category is null or candidate.category = :category)
                      and (:operation is null or candidate.suggestedOperation = :operation)
                    order by sourceEpisode.episodeNo asc, candidate.createdAt asc, candidate.id asc
                    """,
            countQuery = """
                    select count(candidate)
                    from WorldSettingCandidate candidate
                    join candidate.analysisJob analysisJob
                    where candidate.work.id = :workId
                      and analysisJob.batch.id = :batchId
                      and (:reviewStatus is null or candidate.reviewStatus = :reviewStatus)
                      and (:category is null or candidate.category = :category)
                      and (:operation is null or candidate.suggestedOperation = :operation)
                    """
    )
    Page<WorldSettingCandidate> findReviewPage(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("reviewStatus") WorldSettingReviewStatus reviewStatus,
            @Param("category") WorldSettingCategory category,
            @Param("operation") WorldSettingOperation operation,
            Pageable pageable
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
                       and candidate.comparisonStatus = :recomparisonRequired then 1 else 0 end), 0)
                       as recomparisonRequiredCount
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
            @Param("recomparisonRequired") WorldSettingComparisonStatus recomparisonRequired
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
}
