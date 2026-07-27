package org.monitoring.catchholebackend.domain.work.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.exception.WorkErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkRepository extends JpaRepository<Work, UUID> {

    Optional<Work> findByIdAndMemberId(UUID id, Long memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select targetWork from Work targetWork where targetWork.id = :id")
    Optional<Work> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select targetWork
            from Work targetWork
            where targetWork.id = :id
              and targetWork.member.id = :memberId
            """)
    Optional<Work> findByIdAndMemberIdForUpdate(
            @Param("id") UUID id,
            @Param("memberId") Long memberId
    );

    List<Work> findAllByMemberIdOrderByCreatedAtDesc(Long memberId);

    default Work getOwnedWork(UUID id, Long memberId) {
        return findByIdAndMemberId(id, memberId)
                .orElseThrow(() -> new AppException(WorkErrorCode.WORK_NOT_FOUND));
    }

    default Work getOwnedWorkForUpdate(UUID id, Long memberId) {
        return findByIdAndMemberIdForUpdate(id, memberId)
                .orElseThrow(() -> new AppException(WorkErrorCode.WORK_NOT_FOUND));
    }
}
