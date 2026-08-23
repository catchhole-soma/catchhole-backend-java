package org.monitoring.catchholebackend.domain.analysis.mapper;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisBatchJobGroupResponse;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisBatchSummaryResponse;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisBatchPageRow;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisBatchStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateBatchReviewCounts;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateBatchReviewCounts;
import org.springframework.stereotype.Component;

@Component
public class AnalysisBatchMapper {

    public AnalysisBatchJobGroupResponse toJobGroupResponse(
            AnalysisJobType jobType,
            AnalysisBatchStatus status,
            List<AnalysisJob> currentJobs
    ) {
        return new AnalysisBatchJobGroupResponse(
                jobType,
                status,
                currentJobs.size(),
                countStatus(currentJobs, AnalysisJobStatus.PENDING),
                countStatus(currentJobs, AnalysisJobStatus.RUNNING),
                countStatus(currentJobs, AnalysisJobStatus.SUCCEEDED),
                countStatus(currentJobs, AnalysisJobStatus.FAILED),
                countStatus(currentJobs, AnalysisJobStatus.CANCELED),
                currentJobs.stream().map(AnalysisJob::getId).distinct().toList(),
                currentJobs.stream()
                        .map(AnalysisJob::getUpdatedAt)
                        .max(Comparator.naturalOrder())
                        .orElse(null)
        );
    }

    public AnalysisBatchSummaryResponse toResponse(
            AnalysisBatchPageRow pageRow,
            UploadBatch batch,
            AnalysisBatchStatus status,
            Integer episodeStartNo,
            Integer episodeEndNo,
            int episodeCount,
            SettingCandidateBatchReviewCounts characterCandidateCounts,
            WorldSettingCandidateBatchReviewCounts worldSettingCandidateCounts,
            List<AnalysisBatchJobGroupResponse> jobGroups,
            LocalDateTime lastActivityAt
    ) {
        return new AnalysisBatchSummaryResponse(
                pageRow.getBatchId(),
                batch.getUploadType(),
                status,
                episodeStartNo,
                episodeEndNo,
                episodeCount,
                characterCandidateCounts == null ? 0 : characterCandidateCounts.getTotalCandidateCount(),
                characterCandidateCounts == null ? 0 : characterCandidateCounts.getReviewedCandidateCount(),
                characterCandidateCounts == null ? 0 : characterCandidateCounts.getPendingCandidateCount(),
                worldSettingCandidateCounts == null ? 0 : worldSettingCandidateCounts.getTotalCandidateCount(),
                worldSettingCandidateCounts == null ? 0 : worldSettingCandidateCounts.getReviewedCandidateCount(),
                worldSettingCandidateCounts == null ? 0 : worldSettingCandidateCounts.getPendingCandidateCount(),
                worldSettingCandidateCounts == null
                        ? 0
                        : worldSettingCandidateCounts.getTokenInterruptedComparisonCount(),
                worldSettingCandidateCounts != null
                        && worldSettingCandidateCounts.getTokenInterruptedComparisonCount() > 0,
                jobGroups,
                pageRow.getFirstRequestedAt(),
                pageRow.getLastRequestedAt(),
                lastActivityAt
        );
    }

    private int countStatus(List<AnalysisJob> jobs, AnalysisJobStatus status) {
        return (int) jobs.stream()
                .filter(job -> job.getStatus() == status)
                .count();
    }
}
