package org.monitoring.catchholebackend.domain.aitoken.repository;

import jakarta.persistence.LockModeType;

import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenUsage;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenPurpose;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiTokenUsageRepository extends JpaRepository<AiTokenUsage, UUID> {
    List<AiTokenUsage> findAllByAnalysisJobId(UUID analysisJobId);

    List<AiTokenUsage> findAllByValidationJobId(UUID validationJobId);

    List<AiTokenUsage> findAllByChatRunId(UUID chatRunId);

    long countByChatRunIsNotNullAndStatus(AiTokenUsageStatus status);

    @Query(
            """
            select count(usage) > 0 from AiTokenUsage usage
            join usage.analysisJob job
            where job.work.id = :workId
              and job.episode.id = :episodeId
              and job.sourceEpisodeNo = :episodeNo
              and job.sourceContentHash = :sourceHash
              and job.sourceContentS3Key = :sourceKey
              and (job.sourceContentS3Version = :sourceVersion
                   or (job.sourceContentS3Version is null and :sourceVersion is null))
              and usage.purpose in :purposes
            """)
    boolean existsByAnalysisSourceAndPurposeIn(
            @Param("workId") UUID workId,
            @Param("episodeId") UUID episodeId,
            @Param("episodeNo") int episodeNo,
            @Param("sourceHash") String sourceHash,
            @Param("sourceKey") String sourceKey,
            @Param("sourceVersion") String sourceVersion,
            @Param("purposes") List<AiTokenPurpose> purposes);

    @Query("select usage.analysisJob.id from AiTokenUsage usage where usage.requestId = :requestId")
    Optional<UUID> findAnalysisJobIdByRequestId(@Param("requestId") UUID requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AiTokenUsage> findByRequestId(UUID requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<AiTokenUsage> findAllByAnalysisJobIdAndStatus(
            UUID analysisJobId, AiTokenUsageStatus status);

    @Query(
            """
            select coalesce(sum(usage.inputTokens), 0) as inputTokens,
                   coalesce(sum(usage.outputTokens), 0) as outputTokens
            from AiTokenUsage usage
            where usage.analysisJob.id = :analysisJobId
              and usage.status = :status
            """)
    AiTokenTotals sumSettledTokensByAnalysisJobId(
            @Param("analysisJobId") UUID analysisJobId, @Param("status") AiTokenUsageStatus status);
}
