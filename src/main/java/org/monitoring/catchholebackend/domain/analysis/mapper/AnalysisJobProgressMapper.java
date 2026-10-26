package org.monitoring.catchholebackend.domain.analysis.mapper;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisJobProgressEpisodeResponse;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisJobProgressResponse;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisRunResponse;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobProgressEpisodeRow;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobProgressRow;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.springframework.stereotype.Component;

@Component
public class AnalysisJobProgressMapper {
    public List<AnalysisJobProgressResponse> toResponseList(
            List<AnalysisJobProgressRow> jobs, List<AnalysisJobProgressEpisodeRow> episodes
    ) {
        var episodesByJob = episodes.stream().collect(Collectors.groupingBy(
                AnalysisJobProgressEpisodeRow::analysisJobId,
                Collectors.mapping(row -> new AnalysisJobProgressEpisodeResponse(
                        row.id(), row.episodeNo(), row.title(), row.status(), row.updatedAt()), Collectors.toList())));
        return jobs.stream().map(job -> toResponse(job, episodesByJob.getOrDefault(job.id(), List.of()))).toList();
    }

    private AnalysisJobProgressResponse toResponse(AnalysisJobProgressRow job, List<AnalysisJobProgressEpisodeResponse> episodes) {
        boolean ordered = job.analysisMode() == AnalysisMode.ORDERED_PROVISIONAL;
        boolean interrupted = !ordered && job.jobType() == AnalysisJobType.SETTING_EXTRACTION
                && job.status() == AnalysisJobStatus.FAILED
                && job.failureCode() == AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED
                && job.checkpointStage() != null
                && job.checkpointStage().compareTo(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED) >= 0;
        return new AnalysisJobProgressResponse(job.id(), job.episodeId(), job.jobType(), job.status(), job.reviewMode(),
                job.automaticAppliedAt(), job.status() == AnalysisJobStatus.FAILED ? AnalysisFailureCode.orUnexpected(job.failureCode()) : null,
                interrupted, ordered ? new AnalysisRunResponse(job.analysisMode(), job.analysisRunId(), job.runGeneration(),
                        job.runSequence(), job.predecessorJobId(), job.journalStatus(), job.journalInvalidationReason()) : null,
                episodes);
    }
}
