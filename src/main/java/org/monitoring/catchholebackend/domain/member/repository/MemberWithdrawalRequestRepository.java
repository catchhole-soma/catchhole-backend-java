package org.monitoring.catchholebackend.domain.member.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.member.entity.MemberWithdrawalRequest;
import org.monitoring.catchholebackend.domain.member.type.MemberWithdrawalStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberWithdrawalRequestRepository extends JpaRepository<MemberWithdrawalRequest, UUID> {

    Optional<MemberWithdrawalRequest> findByMemberId(Long memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from MemberWithdrawalRequest request where request.id = :id")
    Optional<MemberWithdrawalRequest> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select request.id
            from MemberWithdrawalRequest request
            where request.status in :statuses
              and request.nextAttemptAt <= :now
            order by request.nextAttemptAt asc, request.requestedAt asc
            """)
    List<UUID> findReadyIds(
            @Param("statuses") List<MemberWithdrawalStatus> statuses,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    @Modifying
    @Query("delete from MemberWithdrawalRequest request where request.retentionExpiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
