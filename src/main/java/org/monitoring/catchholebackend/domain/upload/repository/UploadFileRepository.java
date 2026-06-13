package org.monitoring.catchholebackend.domain.upload.repository;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileRole;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadFileRepository extends JpaRepository<UploadFile, UUID> {

    List<UploadFile> findAllByBatchIdOrderByCreatedAtAsc(UUID batchId);

    List<UploadFile> findAllByBatchIdAndFileRole(UUID batchId, UploadFileRole fileRole);
}
