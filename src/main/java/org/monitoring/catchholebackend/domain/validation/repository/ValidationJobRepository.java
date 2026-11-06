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
     * 현재·유효 report에서 lease가 남은 RUNNING Job, 대기 잠금 시간 안에 상태가 바뀐 QUEUED Job(실행 단계), 그리고 확정 전 검수의
     * COMPLETED Job 중 검토 종료 receipt가 아직 없는 것(검토 단계)을 잠근다. PARTIAL·FAILED·CANCELED는 잠그지 않아 깨진 실행이 설정을 묶지 않고,
     * DIAGNOSTIC_ONLY는 검토 종료가 없으므로 실행 단계만 잠근다. 사람의 쓰기 판정과 분석 Job claim이 같은 식을 쓴다.
     */
    String SETTINGS_LOCK = """
            lockReport.current = true
            and lockReport.status <> org.monitoring.catchholebackend.domain.validation.type.ValidationReportStatus.INVALIDATED
            and ((lockJob.status = org.monitoring.catchholebackend.domain.validation.type.ValidationExecutionStatus.RUNNING
                    and lockJob.leaseExpiresAt > :lockNow)
                or (lockJob.status = org.monitoring.catchholebackend.domain.validation.type.ValidationExecutionStatus.QUEUED
                    and lockJob.updatedAt > :lockQueuedCutoff)
                or (lockJob.status = org.monitoring.catchholebackend.domain.validation.type.ValidationExecutionStatus.COMPLETED
                    and lockReport.mode = org.monitoring.catchholebackend.domain.validation.type.ValidationReportMode.BEFORE_CONFIRMATION
                    and not exists (select lockCompletion.id from ValidationReviewCompletion lockCompletion where lockCompletion.report = lockReport)))
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
