package org.monitoring.catchholebackend.domain.upload.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileRole;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadFileRepository extends JpaRepository<UploadFile, UUID> {

    List<UploadFile> findAllByBatchIdOrderByCreatedAtAsc(UUID batchId);

    @EntityGraph(attributePaths = "batch")
    List<UploadFile> findAllByBatchIdIn(Collection<UUID> batchIds);

    @EntityGraph(attributePaths = "batch")
    List<UploadFile> findAllByIdIn(Collection<UUID> ids);

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
