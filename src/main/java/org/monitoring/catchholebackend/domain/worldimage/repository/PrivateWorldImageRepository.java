package org.monitoring.catchholebackend.domain.worldimage.repository;

import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldimage.entity.PrivateWorldImage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrivateWorldImageRepository extends JpaRepository<PrivateWorldImage, UUID> {
    Page<PrivateWorldImage> findAllByWorkId(UUID workId, Pageable pageable);
    Optional<PrivateWorldImage> findByIdAndWorkId(UUID id, UUID workId);
    long countByWorkId(UUID workId);
}
