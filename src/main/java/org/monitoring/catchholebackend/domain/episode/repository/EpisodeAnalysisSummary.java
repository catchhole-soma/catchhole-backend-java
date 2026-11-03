package org.monitoring.catchholebackend.domain.episode.repository;

import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;

/** 회차 목록의 표시 상태에 필요한 값만 읽고 분석 입력과 이력 JSON은 불러오지 않는다. */
public record EpisodeAnalysisSummary(
        UUID id,
        UUID batchId,
        UUID episodeId,
        AnalysisJobStatus status,
        AnalysisMode analysisMode,
        AnalysisReviewMode reviewMode,
        AnalysisJournalStatus journalStatus,
        LocalDateTime automaticAppliedAt,
        LocalDateTime reviewApplicationHeldAt,
        String summaryJson
) {
    public boolean isOrderedProvisional() {
        return analysisMode == AnalysisMode.ORDERED_PROVISIONAL;
    }

    public boolean isCompletedOrderedAnalysis() {
        return isOrderedProvisional() && status == AnalysisJobStatus.SUCCEEDED
                && journalStatus == AnalysisJournalStatus.SEALED
                && (reviewMode != AnalysisReviewMode.AUTOMATIC
                    || automaticAppliedAt != null || reviewApplicationHeldAt != null);
    }
}
