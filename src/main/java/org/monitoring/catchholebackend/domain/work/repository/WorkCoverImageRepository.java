package org.monitoring.catchholebackend.domain.work.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.work.entity.WorkCoverImage;
import org.monitoring.catchholebackend.domain.work.type.WorkCoverImageStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface WorkCoverImageRepository extends JpaRepository<WorkCoverImage, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select image from WorkCoverImage image where image.id = :id")
    Optional<WorkCoverImage> findByIdForUpdate(UUID id);

    long countByWorkId(UUID workId);

    @Query("""
        select image.id from WorkCoverImage image
        where image.status = org.monitoring.catchholebackend.domain.work.type.WorkCoverImageStatus.DELETING
           or (image.status = org.monitoring.catchholebackend.domain.work.type.WorkCoverImageStatus.UPLOADING
               and image.updatedAt < :staleBefore)
        order by coalesce(image.cleanupAttemptedAt, image.updatedAt), image.id
        """)
    List<UUID> findCleanupIds(LocalDateTime staleBefore, Pageable pageable);

    boolean existsByIdAndWorkIdAndStatus(UUID id, UUID workId, WorkCoverImageStatus status);
}
