package org.monitoring.catchholebackend.domain.analysis.processor;

import java.time.LocalDateTime;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.*;

/** Job의 큰 상태 JSON 없이 후보 검토 정책을 평가하는 불변 값이다. */
public record AnalysisReviewState(AnalysisMode analysisMode, AnalysisReviewMode reviewMode,
        AnalysisJobStatus status, AnalysisJournalStatus journalStatus, LocalDateTime automaticAppliedAt,
        boolean currentSourceVersion, LocalDateTime reviewApplicationHeldAt) {
    public AnalysisReviewState(AnalysisMode analysisMode, AnalysisReviewMode reviewMode, AnalysisJobStatus status,
            AnalysisJournalStatus journalStatus, LocalDateTime automaticAppliedAt, boolean currentSourceVersion) {
        this(analysisMode, reviewMode, status, journalStatus, automaticAppliedAt, currentSourceVersion, null);
    }
    public static AnalysisReviewState from(AnalysisJob job) {
        return from(job, job != null && job.hasCurrentSourceVersion());
    }
    public static AnalysisReviewState from(AnalysisJob job, boolean currentSourceVersion) {
        return job == null ? null : new AnalysisReviewState(job.getAnalysisMode(), job.getReviewMode(), job.getStatus(),
                job.getJournalStatus(), job.getAutomaticAppliedAt(), currentSourceVersion, job.getReviewApplicationHeldAt());
    }
    public boolean ordered() { return analysisMode == AnalysisMode.ORDERED_PROVISIONAL; }
    public boolean automatic() { return reviewMode == AnalysisReviewMode.AUTOMATIC; }
    public boolean completedOrdered() {
        return ordered() && status == AnalysisJobStatus.SUCCEEDED && journalStatus == AnalysisJournalStatus.SEALED
                && (!automatic() || automaticStageFinished());
    }
    /** 자동 단계 종료. 실제 반영(automaticAppliedAt) 또는 검수 release의 회차 전체 확정 보류(GH312 S3)다. */
    public boolean automaticStageFinished() { return automaticAppliedAt != null || reviewApplicationHeldAt != null; }
    public boolean completedManual() { return !ordered() && !automatic() && status == AnalysisJobStatus.SUCCEEDED; }
    public boolean automaticPending() {
        return automatic() && automaticAppliedAt == null && (status == AnalysisJobStatus.PENDING || status == AnalysisJobStatus.RUNNING);
    }
}
