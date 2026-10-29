package org.monitoring.catchholebackend.domain.validation.repository;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationJob;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface ValidationJobRepository extends JpaRepository<ValidationJob,UUID> {
    Optional<ValidationJob> findByReportId(UUID reportId);
    @Query("select j.report.work.id from ValidationJob j where j.id=:id") Optional<UUID> findWorkId(@Param("id") UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select j from ValidationJob j where j.id=:id") Optional<ValidationJob> findForUpdate(@Param("id") UUID id);
    @Query("select j.id from ValidationJob j where j.status='QUEUED' or (j.status='RUNNING' and j.leaseExpiresAt < CURRENT_TIMESTAMP) order by j.createdAt,j.id") List<UUID> claimCandidates();
    @Query("select j from ValidationJob j where j.report.work.id=:id order by j.id") List<ValidationJob> findAllByWorkId(@Param("id") UUID id);
}
