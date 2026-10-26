package org.monitoring.catchholebackend.domain.analysis.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisBatchJobGroupResponse;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisBatchCandidateCountsResponse;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobCandidateReviewCounts;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisBatchSummaryRepository.BookSummary;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisBatchSettingBookResponse;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisBatchSummaryResponse;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisBatchPageRow;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisBatchSummaryRepository.GroupSummary;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisBatchStatus;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateBatchReviewCounts;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateBatchReviewCounts;
import org.springframework.stereotype.Component;

@Component
public class AnalysisBatchMapper {
    public AnalysisBatchJobGroupResponse toJobGroupResponse(GroupSummary group) {
        return new AnalysisBatchJobGroupResponse(group.jobType(), group.status(), group.total(),
                group.pending(), group.running(), group.succeeded(), group.failed(), group.canceled(),
                group.currentJobIds(), group.lastActivityAt());
    }

    public AnalysisBatchSettingBookResponse toSettingBookResponse(BookSummary book,
            AnalysisJobCandidateReviewCounts characters, AnalysisJobCandidateReviewCounts worlds) {
        return new AnalysisBatchSettingBookResponse(book.settingBookId(), book.sourceAnalysisJobId(),
                book.batchId(), book.revisionId(), book.settingBookName(), book.status(), book.failureCode(),
                book.currentStep(), book.changed(), book.historical(), book.lastActivityAt(),
                toCandidateCountsResponse(characters), toCandidateCountsResponse(worlds));
    }

    private AnalysisBatchCandidateCountsResponse toCandidateCountsResponse(AnalysisJobCandidateReviewCounts counts) {
        return counts == null ? new AnalysisBatchCandidateCountsResponse(0, 0, 0, 0, 0)
                : new AnalysisBatchCandidateCountsResponse(counts.getTotalCandidateCount(), counts.getReviewedCandidateCount(),
                        counts.getPendingCandidateCount(), counts.getDirectReviewCandidateCount(), counts.getProcessingCandidateCount());
    }

    public AnalysisBatchSummaryResponse toResponse(
            AnalysisBatchPageRow pageRow, AnalysisBatchStatus status, GroupSummary metadata,
            SettingCandidateBatchReviewCounts characters, WorldSettingCandidateBatchReviewCounts worlds,
            List<AnalysisBatchJobGroupResponse> groups, LocalDateTime lastActivityAt,
            List<AnalysisBatchJobGroupResponse> episodeGroups, AnalysisBatchStatus episodeStatus,
            List<AnalysisBatchSettingBookResponse> books) {
        return new AnalysisBatchSummaryResponse(
                pageRow.getBatchId(), pageRow.getUploadType(), status,
                metadata.episodeStartNo(), metadata.episodeEndNo(), metadata.episodeCount(),
                characters == null ? 0 : characters.getTotalCandidateCount(),
                characters == null ? 0 : characters.getReviewedCandidateCount(),
                characters == null ? 0 : characters.getPendingCandidateCount(),
                worlds == null ? 0 : worlds.getTotalCandidateCount(),
                worlds == null ? 0 : worlds.getReviewedCandidateCount(),
                worlds == null ? 0 : worlds.getPendingCandidateCount(),
                worlds == null ? 0 : worlds.getTokenInterruptedComparisonCount(),
                worlds != null && worlds.canResumeTokenInterruptedComparisons(), groups,
                pageRow.getFirstRequestedAt(), pageRow.getLastRequestedAt(), lastActivityAt,
                episodeGroups, episodeStatus, books);
    }
}
