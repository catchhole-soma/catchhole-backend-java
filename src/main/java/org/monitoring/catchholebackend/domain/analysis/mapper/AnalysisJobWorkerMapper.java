package org.monitoring.catchholebackend.domain.analysis.mapper;

import java.util.List;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisEpisodePayload;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobPayload;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.springframework.stereotype.Component;

@Component
public class AnalysisJobWorkerMapper {

    public WorkerAnalysisJobPayload toPayload(AnalysisJob analysisJob, List<Episode> episodes) {
        return new WorkerAnalysisJobPayload(
                analysisJob.getId(),
                analysisJob.getJobType(),
                analysisJob.getWork().getId(),
                analysisJob.getWork().getTitle(),
                analysisJob.getBatch().getId(),
                analysisJob.getModelName(),
                analysisJob.getCurrentStep(),
                episodes.stream()
                        .map(this::toEpisodePayload)
                        .toList()
        );
    }

    private WorkerAnalysisEpisodePayload toEpisodePayload(Episode episode) {
        return new WorkerAnalysisEpisodePayload(
                episode.getId(),
                episode.getEpisodeNo(),
                episode.getTitle(),
                episode.getContentS3Key(),
                episode.getContentS3Version(),
                episode.getContentHash(),
                episode.getCharCount()
        );
    }
}
