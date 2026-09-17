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
    Page<PrivateWorldImage> findAllByWorkIdAndStatus(UUID workId,
            org.monitoring.catchholebackend.domain.worldimage.type.PrivateWorldImageStatus status, Pageable pageable);
    Optional<PrivateWorldImage> findByIdAndWorkIdAndStatus(UUID id, UUID workId,
            org.monitoring.catchholebackend.domain.worldimage.type.PrivateWorldImageStatus status);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select image from PrivateWorldImage image where image.id = :id")
    Optional<PrivateWorldImage> findByIdForUpdate(UUID id);

    @org.springframework.data.jpa.repository.Query("""
            select image.id from PrivateWorldImage image
            where image.status = org.monitoring.catchholebackend.domain.worldimage.type.PrivateWorldImageStatus.DELETING
               or (image.status = org.monitoring.catchholebackend.domain.worldimage.type.PrivateWorldImageStatus.UPLOADING
                   and image.updatedAt < :staleBefore)
            order by image.updatedAt, image.id
            """)
    java.util.List<UUID> findCleanupIds(java.time.LocalDateTime staleBefore, Pageable pageable);
}
