package org.monitoring.catchholebackend.domain.analysis.event;

import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.*;

/** Entity 객체를 commit callback으로 넘기지 않는다. */
public record AnalysisMetricsEvent(String kind, UUID sourceId, int attemptNo, Labels labels,
                                   LocalDateTime from, LocalDateTime at, String detail) {
    public record Labels(AnalysisJobType jobType, AnalysisMode analysisMode, AnalysisReviewMode reviewMode) {
        public static Labels of(AnalysisJob job) { return new Labels(job.getJobType(), job.getAnalysisMode(), job.getReviewMode()); }
    }
}
