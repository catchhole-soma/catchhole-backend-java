package org.monitoring.catchholebackend.domain.analysis.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID> {

    Optional<AnalysisJob> findByIdAndWorkId(UUID id, UUID workId);

    List<AnalysisJob> findAllByWorkIdOrderByCreatedAtDesc(UUID workId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select analysisJob
            from AnalysisJob analysisJob
            join fetch analysisJob.work
            left join fetch analysisJob.batch
            where analysisJob.status = :status
            order by analysisJob.createdAt asc
            """)
    List<AnalysisJob> findClaimCandidates(
            @Param("status") AnalysisJobStatus status,
            Pageable pageable
    );
}
