package org.monitoring.catchholebackend.domain.analysis.processor;

import java.time.LocalDateTime;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.*;

/** Job의 큰 상태 JSON 없이 후보 검토 정책을 평가하는 불변 값이다. */
public record AnalysisReviewState(AnalysisMode analysisMode, AnalysisReviewMode reviewMode,
        AnalysisJobStatus status, AnalysisJournalStatus journalStatus, LocalDateTime automaticAppliedAt,
        boolean currentSourceVersion) {
    public static AnalysisReviewState from(AnalysisJob job) {
        return from(job, job != null && job.hasCurrentSourceVersion());
    }
    public static AnalysisReviewState from(AnalysisJob job, boolean currentSourceVersion) {
        return job == null ? null : new AnalysisReviewState(job.getAnalysisMode(), job.getReviewMode(), job.getStatus(),
                job.getJournalStatus(), job.getAutomaticAppliedAt(), currentSourceVersion);
    }
    public boolean ordered() { return analysisMode == AnalysisMode.ORDERED_PROVISIONAL; }
    public boolean automatic() { return reviewMode == AnalysisReviewMode.AUTOMATIC; }
    public boolean completedOrdered() {
        return ordered() && status == AnalysisJobStatus.SUCCEEDED && journalStatus == AnalysisJournalStatus.SEALED
                && (!automatic() || automaticAppliedAt != null);
    }
    public boolean completedManual() { return !ordered() && !automatic() && status == AnalysisJobStatus.SUCCEEDED; }
    public boolean automaticPending() {
        return automatic() && automaticAppliedAt == null && (status == AnalysisJobStatus.PENDING || status == AnalysisJobStatus.RUNNING);
    }
}
