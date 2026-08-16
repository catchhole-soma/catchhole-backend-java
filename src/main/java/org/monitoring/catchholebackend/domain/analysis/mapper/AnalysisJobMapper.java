package org.monitoring.catchholebackend.domain.analysis.mapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisJobResponse;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisJobTargetResponse;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisJobEpisodeResponse;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.springframework.stereotype.Component;

@Component
public class AnalysisJobMapper {

    public AnalysisJobResponse toResponse(
            AnalysisJob analysisJob,
            List<UploadFile> uploadFiles,
            List<Episode> episodes
    ) {
        return new AnalysisJobResponse(
                analysisJob.getId(),
                analysisJob.getWork().getId(),
                analysisJob.getWork().getTitle(),
                analysisJob.getBatch() == null ? null : analysisJob.getBatch().getId(),
                toTargetResponse(analysisJob.getBatch(), uploadFiles),
                analysisJob.getEpisode() == null ? null : analysisJob.getEpisode().getId(),
                episodes.stream().map(this::toEpisodeResponse).toList(),
                analysisJob.getJobType(),
                analysisJob.getStatus(),
                analysisJob.getCurrentStep(),
                analysisJob.getModelName(),
                analysisJob.getInputTokenCount(),
                analysisJob.getOutputTokenCount(),
                analysisJob.getSummaryJson(),
                publicFailureCode(analysisJob) == null
                        ? null
                        : publicFailureCode(analysisJob).getPublicMessage(),
                publicFailureCode(analysisJob),
                analysisJob.isResumableTokenInterruption(),
                analysisJob.getStartedAt(),
                analysisJob.getCompletedAt(),
                analysisJob.getCreatedAt(),
                analysisJob.getUpdatedAt()
        );
    }

    private AnalysisFailureCode publicFailureCode(AnalysisJob analysisJob) {
        if (analysisJob.getStatus() != AnalysisJobStatus.FAILED) {
            return null;
        }
        return AnalysisFailureCode.orUnexpected(analysisJob.getFailureCode());
    }

    public List<AnalysisJobResponse> toResponseList(
            List<AnalysisJob> analysisJobs,
            Map<UUID, List<UploadFile>> uploadFilesByBatchId,
            Map<UUID, List<Episode>> episodesByJobId
    ) {
        return analysisJobs.stream()
                .map(analysisJob -> toResponse(
                        analysisJob,
                        analysisJob.getBatch() == null
                                ? List.of()
                                : uploadFilesByBatchId.getOrDefault(analysisJob.getBatch().getId(), List.of()),
                        episodesByJobId.getOrDefault(analysisJob.getId(), List.of())
                ))
                .toList();
    }

    private AnalysisJobEpisodeResponse toEpisodeResponse(Episode episode) {
        return new AnalysisJobEpisodeResponse(
                episode.getId(),
                episode.getEpisodeNo(),
                episode.getTitle(),
                episode.getStatus(),
                null,
                episode.getUpdatedAt()
        );
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
                .map(UploadFile::getEpisodeStartNo)
                .filter(value -> value != null)
                .min(Integer::compareTo)
                .orElse(null);
    }

    private Integer maxEpisodeEndNo(List<UploadFile> uploadFiles) {
        return uploadFiles.stream()
                .map(UploadFile::getEpisodeEndNo)
                .filter(value -> value != null)
                .max(Integer::compareTo)
                .orElse(null);
    }

    private Integer sumEpisodeCount(List<UploadFile> uploadFiles) {
        List<Integer> episodeCounts = uploadFiles.stream()
                .map(UploadFile::getEpisodeCount)
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
