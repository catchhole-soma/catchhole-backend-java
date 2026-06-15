package org.monitoring.catchholebackend.domain.analysis.mapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisJobResponse;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisJobTargetResponse;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.springframework.stereotype.Component;

@Component
public class AnalysisJobMapper {

    public AnalysisJobResponse toResponse(AnalysisJob analysisJob, List<UploadFile> uploadFiles) {
        return new AnalysisJobResponse(
                analysisJob.getId(),
                analysisJob.getWork().getId(),
                analysisJob.getWork().getTitle(),
                analysisJob.getBatch() == null ? null : analysisJob.getBatch().getId(),
                toTargetResponse(analysisJob.getBatch(), uploadFiles),
                analysisJob.getEpisode() == null ? null : analysisJob.getEpisode().getId(),
                analysisJob.getJobType(),
                analysisJob.getStatus(),
                analysisJob.getCurrentStep(),
                analysisJob.getModelName(),
                analysisJob.getInputTokenCount(),
                analysisJob.getOutputTokenCount(),
                analysisJob.getSummaryJson(),
                analysisJob.getErrorMessage(),
                analysisJob.getStartedAt(),
                analysisJob.getCompletedAt(),
                analysisJob.getCreatedAt(),
                analysisJob.getUpdatedAt()
        );
    }

    public List<AnalysisJobResponse> toResponseList(
            List<AnalysisJob> analysisJobs,
            Map<UUID, List<UploadFile>> uploadFilesByBatchId
    ) {
        return analysisJobs.stream()
                .map(analysisJob -> toResponse(
                        analysisJob,
                        analysisJob.getBatch() == null
                                ? List.of()
                                : uploadFilesByBatchId.getOrDefault(analysisJob.getBatch().getId(), List.of())
                ))
                .toList();
    }

    private AnalysisJobTargetResponse toTargetResponse(UploadBatch batch, List<UploadFile> uploadFiles) {
        if (batch == null) {
            return null;
        }
        return new AnalysisJobTargetResponse(
                batch.getId(),
                batch.getUploadType(),
                batch.getSourceType(),
                batch.getStatus(),
                batch.getFileCount(),
                minEpisodeStartNo(uploadFiles),
                maxEpisodeEndNo(uploadFiles),
                sumEpisodeCount(uploadFiles)
        );
    }

    private Integer minEpisodeStartNo(List<UploadFile> uploadFiles) {
        return uploadFiles.stream()
                .map(UploadFile::getDetectedEpisodeStartNo)
                .filter(value -> value != null)
                .min(Integer::compareTo)
                .orElse(null);
    }

    private Integer maxEpisodeEndNo(List<UploadFile> uploadFiles) {
        return uploadFiles.stream()
                .map(UploadFile::getDetectedEpisodeEndNo)
                .filter(value -> value != null)
                .max(Integer::compareTo)
                .orElse(null);
    }

    private Integer sumEpisodeCount(List<UploadFile> uploadFiles) {
        List<Integer> episodeCounts = uploadFiles.stream()
                .map(UploadFile::getDetectedEpisodeCount)
                .filter(value -> value != null)
                .toList();
        if (episodeCounts.isEmpty()) {
            return null;
        }
        return episodeCounts.stream()
                .mapToInt(Integer::intValue)
                .sum();
    }
}
