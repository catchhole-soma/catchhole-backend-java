package org.monitoring.catchholebackend.domain.aitoken.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenExtensionRequest;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenExtensionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiTokenExtensionRequestRepository extends JpaRepository<AiTokenExtensionRequest, UUID> {

    Optional<AiTokenExtensionRequest> findFirstByMemberIdAndStatusOrderByCreatedAtDesc(
            Long memberId,
            AiTokenExtensionStatus status
    );

    Page<AiTokenExtensionRequest> findAllByStatusOrderByCreatedAtAsc(
            AiTokenExtensionStatus status,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from AiTokenExtensionRequest request where request.id = :requestId")
    Optional<AiTokenExtensionRequest> findByIdForUpdate(@Param("requestId") UUID requestId);
}
