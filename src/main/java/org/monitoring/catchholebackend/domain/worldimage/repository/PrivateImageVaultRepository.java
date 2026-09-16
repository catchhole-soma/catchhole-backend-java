package org.monitoring.catchholebackend.domain.worldimage.repository;

import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldimage.entity.PrivateImageVault;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrivateImageVaultRepository extends JpaRepository<PrivateImageVault, UUID> {
    Optional<PrivateImageVault> findByMemberId(Long memberId);
}
