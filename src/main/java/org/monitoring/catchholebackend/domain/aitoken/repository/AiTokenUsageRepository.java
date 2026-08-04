package org.monitoring.catchholebackend.domain.aitoken.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenUsage;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiTokenUsageRepository extends JpaRepository<AiTokenUsage, UUID> {

    @Query("select usage.analysisJob.id from AiTokenUsage usage where usage.requestId = :requestId")
    Optional<UUID> findAnalysisJobIdByRequestId(@Param("requestId") UUID requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AiTokenUsage> findByRequestId(UUID requestId);

    @Query("""
            select coalesce(sum(usage.inputTokens), 0) as inputTokens,
                   coalesce(sum(usage.outputTokens), 0) as outputTokens
            from AiTokenUsage usage
            where usage.analysisJob.id = :analysisJobId
              and usage.status = :status
            """)
    AiTokenTotals sumSettledTokensByAnalysisJobId(
            @Param("analysisJobId") UUID analysisJobId,
            @Param("status") AiTokenUsageStatus status
    );
}
