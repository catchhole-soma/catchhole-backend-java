package org.monitoring.catchholebackend.domain.validation.repository;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationReviewStages;
import org.monitoring.catchholebackend.domain.validation.type.*;

/** 회차 목록은 큰 seed/entries 없이 검수 표시 상태만 읽는다. reviewStage는 같은 query의 scalar 값으로 계산한다(GH312 S4). */
public record ValidationReportState(UUID episodeId, UUID reportId, ValidationReportStatus status, ValidationReviewStage reviewStage) {
    /** JPQL constructor projection. receipt가 없으면 release Job·회차 전체 확정 값은 쓰지 않는다. */
    public ValidationReportState(UUID episodeId, UUID reportId, ValidationReportStatus status, ValidationPreparationStatus baselineStatus, boolean current,
            ValidationExecutionStatus execution, UUID completionId, AnalysisJobStatus extractionStatus, AnalysisJournalStatus extractionJournal,
            ValidationApplicationStatus application) {
        this(episodeId, reportId, status, ValidationReviewStages.resolve(new ValidationReviewStages.Input(current, status, baselineStatus, execution, completionId != null,
                completionId == null ? ValidationExtractionState.NOT_STARTED : ValidationReviewStages.extractionState(extractionStatus, extractionJournal),
                completionId == null ? null : application)));
    }
}
