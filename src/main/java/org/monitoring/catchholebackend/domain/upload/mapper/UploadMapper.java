package org.monitoring.catchholebackend.domain.upload.mapper;

import java.util.List;
import org.monitoring.catchholebackend.domain.upload.dto.response.UploadBatchResponse;
import org.monitoring.catchholebackend.domain.upload.dto.response.UploadFileResponse;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileRole;
import org.springframework.stereotype.Component;

@Component
public class UploadMapper {

    public UploadFile toEntity(
            UploadBatch batch,
            UploadFileRole fileRole,
            String originalFilename,
            String mimeType,
            String storageUrl,
            long fileSize
    ) {
        return UploadFile.create(batch, fileRole, originalFilename, mimeType, storageUrl, fileSize);
    }

    public UploadFileResponse toFileResponse(UploadFile uploadFile) {
        return new UploadFileResponse(
                uploadFile.getId(),
                uploadFile.getFileRole(),
                uploadFile.getOriginalFilename(),
                uploadFile.getMimeType(),
                uploadFile.getStorageUrl(),
                uploadFile.getFileSize(),
                uploadFile.getDetectedEpisodeStartNo(),
                uploadFile.getDetectedEpisodeEndNo(),
                uploadFile.getDetectedEpisodeCount(),
                uploadFile.getParseStatus()
        );
    }

    public List<UploadFileResponse> toFileResponseList(List<UploadFile> uploadFiles) {
        return uploadFiles.stream()
                .map(this::toFileResponse)
                .toList();
    }

    public UploadBatchResponse toBatchResponse(UploadBatch uploadBatch, List<UploadFile> uploadFiles) {
        return new UploadBatchResponse(
                uploadBatch.getId(),
                uploadBatch.getWork().getId(),
                uploadBatch.getUploadedBy().getId(),
                uploadBatch.getUploadType(),
                uploadBatch.getSourceType(),
                uploadBatch.getStatus(),
                uploadBatch.getFileCount(),
                uploadBatch.getCompletedAt(),
                toFileResponseList(uploadFiles)
        );
    }
}
