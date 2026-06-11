package org.monitoring.catchholebackend.domain.upload.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadBatchRepository extends JpaRepository<UploadBatch, UUID> {

    Optional<UploadBatch> findByIdAndWorkId(UUID id, UUID workId);

    List<UploadBatch> findAllByWorkIdOrderByCreatedAtDesc(UUID workId);
}
