package org.monitoring.catchholebackend.domain.validation.processor;

import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.validation.type.*;

/**
 * GH312 S4 파생 검수 단계의 유일한 계산식. report·ValidationJob·검토 종료 receipt·release Job·회차 전체 확정 receipt의 scalar 값만 받는
 * 순수 함수라 단건 응답({@link ValidationReviewGate})과 목록 projection(원고 목록·배치 요약·이력)이 같은 값을 낸다.
 */
public final class ValidationReviewStages {
    private ValidationReviewStages() {}

    /**
     * @param reviewCompleted 이 report의 검토 종료 receipt 존재. 퇴역 report는 current=false가 먼저 INVALIDATED로 끝난다.
     * @param extraction receipt가 가리키는 release Job 상태({@link #extractionState}). receipt가 없으면 NOT_STARTED
     * @param application 회차 전체 확정 receipt 상태. 시도 전 null
     */
    public record Input(boolean current, ValidationReportStatus status, ValidationPreparationStatus baselineStatus, ValidationExecutionStatus execution,
            boolean reviewCompleted, ValidationExtractionState extraction, ValidationApplicationStatus application) {}

    public static ValidationReviewStage resolve(Input in) {
        if (!in.current() || in.status() == ValidationReportStatus.INVALIDATED || in.baselineStatus() == ValidationPreparationStatus.INVALIDATED) return ValidationReviewStage.INVALIDATED;
        if (in.status() == ValidationReportStatus.HELD_INCOMPLETE || in.baselineStatus() == ValidationPreparationStatus.FAILED) return ValidationReviewStage.HELD_INCOMPLETE;
        if (in.status() != ValidationReportStatus.WAITING_FOR_ERROR_REVIEW || in.baselineStatus() != ValidationPreparationStatus.READY) return ValidationReviewStage.PREPARING_BASELINE;
        if (in.reviewCompleted()) {
            if (in.application() == ValidationApplicationStatus.APPLIED) return ValidationReviewStage.APPLIED;
            if (in.extraction() == ValidationExtractionState.FAILED) return ValidationReviewStage.EXTRACTION_FAILED;
            // 공개 재시도로 release Job이 다시 실행 중이면 이전 시도의 보류 receipt보다 진행 상태가 우선이다.
            if (in.application() != null && in.extraction() != ValidationExtractionState.RUNNING) return ValidationReviewStage.APPLICATION_HELD;
            return ValidationReviewStage.REVIEW_COMPLETED;
        }
        return switch (in.execution() == null ? ValidationExecutionStatus.NOT_STARTED : in.execution()) {
            case COMPLETED, PARTIAL, FAILED -> ValidationReviewStage.AWAITING_REVIEW;
            // 실행 취소는 원문 파기 무효화에서만 생긴다. 유효 report에는 도달하지 않지만 재검수가 필요하다는 뜻으로 맞춘다.
            case CANCELED -> ValidationReviewStage.INVALIDATED;
            case NOT_STARTED, QUEUED, RUNNING -> ValidationReviewStage.VALIDATING;
        };
    }

    /** release Job scalar 상태 → /review extractionState. Job 행이 없으면(파기 경합) FAILED. */
    public static ValidationExtractionState extractionState(AnalysisJobStatus status, AnalysisJournalStatus journal) {
        if (status == null) return ValidationExtractionState.FAILED;
        return switch (status) {
            case PENDING, RUNNING -> ValidationExtractionState.RUNNING;
            case SUCCEEDED -> journal == AnalysisJournalStatus.INCOMPLETE || journal == AnalysisJournalStatus.INVALIDATED ? ValidationExtractionState.FAILED : ValidationExtractionState.SUCCEEDED;
            case FAILED, CANCELED -> ValidationExtractionState.FAILED;
        };
    }
}
