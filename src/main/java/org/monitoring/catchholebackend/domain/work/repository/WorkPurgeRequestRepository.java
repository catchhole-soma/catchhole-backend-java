package org.monitoring.catchholebackend.domain.work.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.work.entity.WorkPurgeRequest;
import org.monitoring.catchholebackend.domain.work.type.WorkPurgeStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkPurgeRequestRepository extends JpaRepository<WorkPurgeRequest, UUID> {

    Optional<WorkPurgeRequest> findByMemberIdAndWorkId(Long memberId, UUID workId);

    Optional<WorkPurgeRequest> findByIdAndMemberId(UUID id, Long memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select request
            from WorkPurgeRequest request
            where request.memberId = :memberId
              and request.status in :statuses
            order by request.requestedAt asc
            """)
    List<WorkPurgeRequest> findAllByMemberIdAndStatusInForUpdate(
            @Param("memberId") Long memberId,
            @Param("statuses") List<WorkPurgeStatus> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from WorkPurgeRequest request where request.id = :id and request.memberId = :memberId")
    Optional<WorkPurgeRequest> findByIdAndMemberIdForUpdate(
            @Param("id") UUID id,
            @Param("memberId") Long memberId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from WorkPurgeRequest request where request.id = :id")
    Optional<WorkPurgeRequest> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select request
            from WorkPurgeRequest request
            where request.status = :status
              and (request.workerDrainUntil is null or request.workerDrainUntil <= :now)
            order by request.requestedAt asc
            """)
    List<WorkPurgeRequest> findReadyForUpdate(
            @Param("status") WorkPurgeStatus status,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select request
            from WorkPurgeRequest request
            where request.status = :status
              and request.processingStartedAt < :staleBefore
            order by request.processingStartedAt asc
            """)
    List<WorkPurgeRequest> findStaleProcessingForUpdate(
            @Param("status") WorkPurgeStatus status,
            @Param("staleBefore") LocalDateTime staleBefore,
            Pageable pageable
    );

    long countByStatusInAndRequestedAtBefore(List<WorkPurgeStatus> statuses, LocalDateTime requestedBefore);

    @Modifying
    @Query("delete from WorkPurgeRequest request where request.retentionExpiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
