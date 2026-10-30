package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.monitoring.catchholebackend.domain.validation.type.ValidationReviewStage.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationReviewGate;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationReviewStages;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationReviewStages.Input;
import org.monitoring.catchholebackend.domain.validation.type.*;

@DisplayName("GH312 S4 파생 검수 단계 계산")
class ValidationReviewStagesTest {
    static final ValidationReportStatus WAITING=ValidationReportStatus.WAITING_FOR_ERROR_REVIEW;
    static final ValidationPreparationStatus READY=ValidationPreparationStatus.READY;
    static ValidationReviewStage ready(ValidationExecutionStatus execution) {return ValidationReviewStages.resolve(new Input(true,WAITING,READY,execution,false,ValidationExtractionState.NOT_STARTED,null));}
    static ValidationReviewStage completed(ValidationExtractionState extraction,ValidationApplicationStatus application) {
        return ValidationReviewStages.resolve(new Input(true,WAITING,READY,ValidationExecutionStatus.COMPLETED,true,extraction,application));
    }

    @Test @DisplayName("기준 준비·보류·무효·퇴역은 실행·receipt보다 먼저 정한다")
    void reportStateComesFirst() {
        assertThat(ValidationReviewStages.resolve(new Input(true,ValidationReportStatus.PREPARING_BASELINE,ValidationPreparationStatus.PENDING,null,false,ValidationExtractionState.NOT_STARTED,null))).isEqualTo(PREPARING_BASELINE);
        assertThat(ValidationReviewStages.resolve(new Input(true,ValidationReportStatus.HELD_INCOMPLETE,ValidationPreparationStatus.FAILED,null,false,ValidationExtractionState.NOT_STARTED,null))).isEqualTo(HELD_INCOMPLETE);
        assertThat(ValidationReviewStages.resolve(new Input(true,ValidationReportStatus.INVALIDATED,ValidationPreparationStatus.INVALIDATED,ValidationExecutionStatus.CANCELED,true,ValidationExtractionState.FAILED,null))).isEqualTo(INVALIDATED);
        assertThat(ValidationReviewStages.resolve(new Input(false,WAITING,READY,ValidationExecutionStatus.COMPLETED,true,ValidationExtractionState.SUCCEEDED,ValidationApplicationStatus.APPLIED)))
                .as("a retired report is history even if it was applied").isEqualTo(INVALIDATED);
    }

    @Test @DisplayName("검토 종료 전에는 실행 상태로 VALIDATING·AWAITING_REVIEW를 나눈다")
    void beforeReviewCompletion() {
        for(var execution:new ValidationExecutionStatus[]{ValidationExecutionStatus.NOT_STARTED,ValidationExecutionStatus.QUEUED,ValidationExecutionStatus.RUNNING})assertThat(ready(execution)).as(execution.name()).isEqualTo(VALIDATING);
        assertThat(ValidationReviewStages.resolve(new Input(true,WAITING,READY,null,false,ValidationExtractionState.NOT_STARTED,null))).as("no job").isEqualTo(VALIDATING);
        for(var execution:new ValidationExecutionStatus[]{ValidationExecutionStatus.COMPLETED,ValidationExecutionStatus.PARTIAL,ValidationExecutionStatus.FAILED})assertThat(ready(execution)).as(execution.name()).isEqualTo(AWAITING_REVIEW);
        assertThat(ready(ValidationExecutionStatus.CANCELED)).isEqualTo(INVALIDATED);
    }

    @Test @DisplayName("검토 종료 뒤에는 반영 > 추출 실패 > 재실행 중 > 보류 > 진행 순서다")
    void afterReviewCompletion() {
        assertThat(completed(ValidationExtractionState.RUNNING,null)).isEqualTo(REVIEW_COMPLETED);
        assertThat(completed(ValidationExtractionState.SUCCEEDED,null)).as("sealed but not yet evaluated").isEqualTo(REVIEW_COMPLETED);
        assertThat(completed(ValidationExtractionState.FAILED,null)).isEqualTo(EXTRACTION_FAILED);
        assertThat(completed(ValidationExtractionState.FAILED,ValidationApplicationStatus.HELD_REVIEW)).as("EXTRACTION_NOT_FINISHED hold").isEqualTo(EXTRACTION_FAILED);
        assertThat(completed(ValidationExtractionState.SUCCEEDED,ValidationApplicationStatus.HELD_REVIEW)).isEqualTo(APPLICATION_HELD);
        assertThat(completed(ValidationExtractionState.RUNNING,ValidationApplicationStatus.HELD_REVIEW)).as("public retry re-runs the release job").isEqualTo(REVIEW_COMPLETED);
        assertThat(completed(ValidationExtractionState.SUCCEEDED,ValidationApplicationStatus.APPLIED)).isEqualTo(APPLIED);
    }

    @ParameterizedTest @EnumSource(AnalysisJobStatus.class) @DisplayName("release Job scalar 상태를 /review extractionState와 같은 규칙으로 바꾼다")
    void extractionStateFromJobScalars(AnalysisJobStatus status) {
        var sealed=ValidationReviewStages.extractionState(status,AnalysisJournalStatus.SEALED);
        switch(status) {
            case PENDING,RUNNING->assertThat(sealed).isEqualTo(ValidationExtractionState.RUNNING);
            case SUCCEEDED->{assertThat(sealed).isEqualTo(ValidationExtractionState.SUCCEEDED);
                assertThat(ValidationReviewStages.extractionState(status,AnalysisJournalStatus.INCOMPLETE)).isEqualTo(ValidationExtractionState.FAILED);
                assertThat(ValidationReviewStages.extractionState(status,AnalysisJournalStatus.INVALIDATED)).isEqualTo(ValidationExtractionState.FAILED);}
            case FAILED,CANCELED->assertThat(sealed).isEqualTo(ValidationExtractionState.FAILED);
        }
        assertThat(ValidationReviewStages.extractionState(null,null)).as("missing job row").isEqualTo(ValidationExtractionState.FAILED);
    }

    @Test @DisplayName("검토 요약의 단계는 같은 계산식을 쓴다")
    void reviewGateUsesSameStage() {
        var review=ValidationReviewGate.evaluate(new ValidationReviewGate.Input(true,READY,WAITING,ValidationReportMode.DIAGNOSTIC_ONLY,ValidationExecutionStatus.COMPLETED,ValidationReviewGate.Counts.EMPTY,true));
        assertThat(review.reviewStage()).as("diagnostic reports stay awaiting review; they can never complete").isEqualTo(AWAITING_REVIEW);
        var applied=ValidationReviewGate.evaluate(new ValidationReviewGate.Input(true,READY,WAITING,ValidationReportMode.BEFORE_CONFIRMATION,ValidationExecutionStatus.COMPLETED,ValidationReviewGate.Counts.EMPTY,true,
                new ValidationReviewGate.Completion(java.util.UUID.randomUUID(),ValidationReviewTrigger.AUTOMATIC,java.time.LocalDateTime.now(),java.util.UUID.randomUUID()),null,
                new ValidationReviewGate.Application(ValidationApplicationStatus.APPLIED,1,java.util.List.of(),java.time.LocalDateTime.now(),1),ValidationExtractionState.SUCCEEDED));
        assertThat(applied.reviewStage()).isEqualTo(APPLIED);
    }
}
