package org.monitoring.catchholebackend.domain.upload.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UploadBatchRepository extends JpaRepository<UploadBatch, UUID> {

    Optional<UploadBatch> findByIdAndWorkId(UUID id, UUID workId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select batch from UploadBatch batch where batch.id = :id")
    Optional<UploadBatch> findByIdForUpdate(@Param("id") UUID id);

    List<UploadBatch> findAllByWorkIdOrderByCreatedAtDesc(UUID workId);
}
