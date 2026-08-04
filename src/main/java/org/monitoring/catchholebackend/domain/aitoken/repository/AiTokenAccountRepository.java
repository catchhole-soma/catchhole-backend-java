package org.monitoring.catchholebackend.domain.aitoken.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AiTokenAccountRepository extends JpaRepository<AiTokenAccount, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AiTokenAccount> findByMemberId(Long memberId);
}
