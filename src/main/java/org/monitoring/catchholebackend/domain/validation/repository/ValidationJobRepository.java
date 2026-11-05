package org.monitoring.catchholebackend.domain.validation.repository;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationJob;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface ValidationJobRepository extends JpaRepository<ValidationJob,UUID> {
    /**
     * #381 확정 설정 잠금 조건. alias {@code lockJob}·{@code lockReport}, 파라미터 {@code :lockNow}(Instant)·{@code :lockQueuedCutoff}(LocalDateTime).
     * 현재·유효 report의 lease가 남은 RUNNING Job과 대기 잠금 시간 안에 상태가 바뀐 QUEUED Job만 잠근다. 사람의 쓰기 판정과 분석 Job claim이 같은 식을 쓴다.
     */
    String SETTINGS_LOCK = """
            lockReport.current = true
            and lockReport.status <> org.monitoring.catchholebackend.domain.validation.type.ValidationReportStatus.INVALIDATED
            and ((lockJob.status = org.monitoring.catchholebackend.domain.validation.type.ValidationExecutionStatus.RUNNING
                    and lockJob.leaseExpiresAt > :lockNow)
                or (lockJob.status = org.monitoring.catchholebackend.domain.validation.type.ValidationExecutionStatus.QUEUED
                    and lockJob.updatedAt > :lockQueuedCutoff))
            """;
    Optional<ValidationJob> findByReportId(UUID reportId);
    @Query("select j.report.work.id from ValidationJob j where j.id=:id") Optional<UUID> findWorkId(@Param("id") UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select j from ValidationJob j where j.id=:id") Optional<ValidationJob> findForUpdate(@Param("id") UUID id);
    @Query("select j.id from ValidationJob j where j.status='QUEUED' or (j.status='RUNNING' and j.leaseExpiresAt < CURRENT_TIMESTAMP) order by j.createdAt,j.id") List<UUID> claimCandidates();
    @Query("select j from ValidationJob j where j.report.work.id=:id order by j.id") List<ValidationJob> findAllByWorkId(@Param("id") UUID id);
    @Query("select new org.monitoring.catchholebackend.domain.validation.repository.ValidationSettingsLockState("
            + "lockReport.id, lockJob.id, lockEpisode.episodeNo, lockJob.status, lockJob.leaseExpiresAt) "
            + "from ValidationJob lockJob join lockJob.report lockReport join lockReport.episode lockEpisode "
            + "where lockReport.work.id = :workId and " + SETTINGS_LOCK + " order by lockJob.createdAt, lockJob.id")
    List<ValidationSettingsLockState> findSettingsLocks(@Param("workId") UUID workId, @Param("lockNow") Instant now,
            @Param("lockQueuedCutoff") LocalDateTime queuedCutoff);
}
