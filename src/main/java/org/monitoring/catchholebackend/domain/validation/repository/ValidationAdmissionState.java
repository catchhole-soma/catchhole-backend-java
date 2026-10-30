package org.monitoring.catchholebackend.domain.validation.repository;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationReviewStages;
import org.monitoring.catchholebackend.domain.validation.type.*;

/**
 * 다음 회차 등록 차단 판정(#314 B1)에 쓰는 작품의 현재 report scalar 상태. reviewStage는 목록 projection과 같은 계산식이다.
 * application은 회차 전체 확정 receipt 상태(시도 전 null)로, APPLIED 뒤 원고 교체 등으로 report가 INVALIDATED가 되어도 그대로 남는다.
 */
public record ValidationAdmissionState(UUID reportId, UUID episodeId, int episodeNo, ValidationReviewStage reviewStage, ValidationApplicationStatus application) {
    /** JPQL constructor projection. receipt가 없으면 release Job·회차 전체 확정 값은 쓰지 않는다({@link ValidationReportState}와 같은 규칙). */
    public ValidationAdmissionState(UUID reportId, UUID episodeId, int episodeNo, ValidationReportStatus status, ValidationPreparationStatus baselineStatus, boolean current,
            ValidationExecutionStatus execution, UUID completionId, AnalysisJobStatus extractionStatus, AnalysisJournalStatus extractionJournal,
            ValidationApplicationStatus application) {
        this(reportId, episodeId, episodeNo, ValidationReviewStages.resolve(new ValidationReviewStages.Input(current, status, baselineStatus, execution, completionId != null,
                completionId == null ? ValidationExtractionState.NOT_STARTED : ValidationReviewStages.extractionState(extractionStatus, extractionJournal),
                completionId == null ? null : application)), completionId == null ? null : application);
    }
}
