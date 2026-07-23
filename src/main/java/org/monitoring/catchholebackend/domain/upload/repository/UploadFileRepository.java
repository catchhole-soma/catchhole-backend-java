package org.monitoring.catchholebackend.domain.upload.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileRole;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadFileRepository extends JpaRepository<UploadFile, UUID> {

    List<UploadFile> findAllByBatchIdOrderByCreatedAtAsc(UUID batchId);

    List<UploadFile> findAllByBatchIdIn(Collection<UUID> batchIds);

    List<UploadFile> findAllByBatchIdAndFileRole(UUID batchId, UploadFileRole fileRole);

    List<UploadFile> findAllByBatchWorkIdAndFileRoleAndArchivedAtIsNullOrderByCreatedAtDesc(
            UUID workId,
            UploadFileRole fileRole
    );

    Optional<UploadFile> findByIdAndBatchWorkIdAndFileRoleAndArchivedAtIsNull(
            UUID id,
            UUID workId,
            UploadFileRole fileRole
    );

    boolean existsByBatchWorkIdAndFileRoleAndOriginalFilenameAndArchivedAtIsNull(
            UUID workId,
            UploadFileRole fileRole,
            String originalFilename
    );
}
