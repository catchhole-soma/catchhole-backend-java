package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.monitoring.catchholebackend.domain.validation.type.ValidationExecutionStatus.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationReviewGate;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationReviewGate.*;
import org.monitoring.catchholebackend.domain.validation.type.*;

@DisplayName("작가 검토 종료 경로와 판단 잠금 계산")
class ValidationReviewGateTest {
    static final Counts DECIDED=new Counts(2,0,0,2,1,0,0,1);
    Review evaluate(boolean current,ValidationPreparationStatus baseline,ValidationReportStatus status,ValidationReportMode mode,ValidationExecutionStatus execution,Counts counts) {
        return ValidationReviewGate.evaluate(new Input(current,baseline,status,mode,execution,counts,true));
    }
    Review ready(ValidationReportMode mode,ValidationExecutionStatus execution,Counts counts) {return evaluate(true,ValidationPreparationStatus.READY,ValidationReportStatus.WAITING_FOR_ERROR_REVIEW,mode,execution,counts);}

    @Test @DisplayName("정상 완료 0건은 자동 추출 후보, 모두 판단한 항목은 명시 검토 완료 대상이다")
    void completedReportsChooseAutomaticOrExplicitPath() {
        var automatic=ready(ValidationReportMode.BEFORE_CONFIRMATION,COMPLETED,Counts.EMPTY);
        assertThat(automatic.completionMode()).isEqualTo(ValidationReviewCompletionMode.AUTOMATIC_ELIGIBLE);
        // GH312 S2: receipt 없는 AUTOMATIC_ELIGIBLE은 /complete의 자동 종료가 추출 생성 조건에 막힌 경우뿐이라 명시 요청으로 다시 시도할 수 있다.
        assertThat(automatic.canCompleteReview()).isTrue();assertThat(automatic.allowedActions()).containsExactly(ValidationReviewAction.COMPLETE_REVIEW);
        assertThat(automatic.holdReasons()).isEmpty();assertThat(automatic.errorReviewStatus()).isEqualTo(ValidationPreparationStatus.READY);
        var explicit=ready(ValidationReportMode.BEFORE_CONFIRMATION,COMPLETED,DECIDED);
        assertThat(explicit.completionMode()).isEqualTo(ValidationReviewCompletionMode.EXPLICIT_REQUIRED);assertThat(explicit.canCompleteReview()).isTrue();
        assertThat(explicit.allowedActions()).containsExactly(ValidationReviewAction.SAVE_JUDGMENT,ValidationReviewAction.COMPLETE_REVIEW);
    }

    @Test @DisplayName("진단 report는 판단을 마쳐도 검토 종료 경로를 열지 않는다")
    void diagnosticOnlyIsAlwaysBlocked() {
        for(var counts:new Counts[]{Counts.EMPTY,DECIDED}) {
            var review=ready(ValidationReportMode.DIAGNOSTIC_ONLY,COMPLETED,counts);
            assertThat(review.completionMode()).isEqualTo(ValidationReviewCompletionMode.BLOCKED);assertThat(review.holdReasons()).containsExactly(ValidationReviewHoldReason.DIAGNOSTIC_ONLY);
            assertThat(review.allowedActions()).doesNotContain(ValidationReviewAction.COMPLETE_REVIEW);assertThat(review.errorReviewStatus()).isEqualTo(ValidationPreparationStatus.PENDING);
        }
        assertThat(ready(ValidationReportMode.DIAGNOSTIC_ONLY,COMPLETED,DECIDED).judgmentLocked()).isFalse();
    }

    @Test @DisplayName("미검토·수정 필요·미완료 실행 사유를 함께 보고한다")
    void blockedReasonsAccumulate() {
        var review=ready(ValidationReportMode.BEFORE_CONFIRMATION,PARTIAL,new Counts(2,1,1,0,1,0,0,1));
        assertThat(review.holdReasons()).containsExactly(ValidationReviewHoldReason.EXECUTION_NOT_COMPLETED,ValidationReviewHoldReason.PENDING_JUDGMENTS,ValidationReviewHoldReason.NEEDS_FIX_PENDING);
        assertThat(review.allowedActions()).containsExactly(ValidationReviewAction.SAVE_JUDGMENT,ValidationReviewAction.RETRY);assertThat(review.errorReviewStatus()).isEqualTo(ValidationPreparationStatus.NOT_STARTED);
    }

    @Test @DisplayName("무효 report와 준비되지 않은 기준은 판단을 잠그고 재검수만 안내한다")
    void invalidatedAndUnpreparedReportsLockJudgments() {
        var invalid=evaluate(true,ValidationPreparationStatus.INVALIDATED,ValidationReportStatus.INVALIDATED,ValidationReportMode.BEFORE_CONFIRMATION,CANCELED,Counts.EMPTY);
        assertThat(invalid.lockReason()).isEqualTo(ValidationReviewLockReason.REPORT_INVALIDATED);assertThat(invalid.holdReasons()).contains(ValidationReviewHoldReason.REPORT_INVALIDATED);
        assertThat(invalid.allowedActions()).containsExactly(ValidationReviewAction.RECHECK);assertThat(invalid.errorReviewStatus()).isEqualTo(ValidationPreparationStatus.INVALIDATED);
        var retired=evaluate(false,ValidationPreparationStatus.INVALIDATED,ValidationReportStatus.INVALIDATED,ValidationReportMode.BEFORE_CONFIRMATION,COMPLETED,DECIDED);
        assertThat(retired.allowedActions()).isEmpty();assertThat(retired.completionMode()).isEqualTo(ValidationReviewCompletionMode.BLOCKED);
        var held=evaluate(true,ValidationPreparationStatus.FAILED,ValidationReportStatus.HELD_INCOMPLETE,ValidationReportMode.DIAGNOSTIC_ONLY,NOT_STARTED,Counts.EMPTY);
        assertThat(held.lockReason()).isEqualTo(ValidationReviewLockReason.BASELINE_NOT_READY);assertThat(held.holdReasons()).contains(ValidationReviewHoldReason.BASELINE_NOT_READY,ValidationReviewHoldReason.DIAGNOSTIC_ONLY);
    }

    @ParameterizedTest @EnumSource(ValidationExecutionStatus.class) @DisplayName("판단 저장은 COMPLETED·PARTIAL·FAILED에서만 열린다")
    void judgmentWindowFollowsExecutionStatus(ValidationExecutionStatus execution) {
        var reason=ValidationReviewGate.lockReason(true,ValidationPreparationStatus.READY,ValidationReportStatus.WAITING_FOR_ERROR_REVIEW,execution,false);
        switch(execution) {
            case COMPLETED,PARTIAL,FAILED->assertThat(reason).isNull();
            case NOT_STARTED->assertThat(reason).isEqualTo(ValidationReviewLockReason.EXECUTION_NOT_STARTED);
            case CANCELED->assertThat(reason).isEqualTo(ValidationReviewLockReason.EXECUTION_CANCELED);
            default->assertThat(reason).isEqualTo(ValidationReviewLockReason.EXECUTION_IN_PROGRESS);
        }
    }

    @Test @DisplayName("현재 report의 검토 종료 receipt는 판단·재시도를 잠그고 COMPLETED를 알린다")
    void completedReviewLocksJudgmentsAndHidesActions() {
        var receipt=new Completion(java.util.UUID.randomUUID(),ValidationReviewTrigger.EXPLICIT,java.time.LocalDateTime.now(),java.util.UUID.randomUUID());
        var completed=ValidationReviewGate.evaluate(new Input(true,ValidationPreparationStatus.READY,ValidationReportStatus.WAITING_FOR_ERROR_REVIEW,ValidationReportMode.BEFORE_CONFIRMATION,COMPLETED,DECIDED,true,receipt));
        assertThat(completed.completionMode()).isEqualTo(ValidationReviewCompletionMode.COMPLETED);assertThat(completed.lockReason()).isEqualTo(ValidationReviewLockReason.REVIEW_COMPLETED);
        // GH312 S3: 검토 종료 뒤 아직 반영하지 않았으면 회차 전체 반영만 안내하고, APPLIED 뒤에는 남은 행동이 없다.
        assertThat(completed.allowedActions()).containsExactly(ValidationReviewAction.APPLY_SETTINGS);assertThat(completed.canCompleteReview()).isFalse();assertThat(completed.completion()).isEqualTo(receipt);
        var applied=ValidationReviewGate.evaluate(new Input(true,ValidationPreparationStatus.READY,ValidationReportStatus.WAITING_FOR_ERROR_REVIEW,ValidationReportMode.BEFORE_CONFIRMATION,COMPLETED,DECIDED,true,receipt,null,
                new ValidationReviewGate.Application(ValidationApplicationStatus.APPLIED,2,java.util.List.of(),java.time.LocalDateTime.now(),1),ValidationExtractionState.SUCCEEDED));
        assertThat(applied.allowedActions()).isEmpty();assertThat(applied.application().status()).isEqualTo(ValidationApplicationStatus.APPLIED);assertThat(applied.extractionState()).isEqualTo(ValidationExtractionState.SUCCEEDED);
        var held=ValidationReviewGate.evaluate(new Input(true,ValidationPreparationStatus.READY,ValidationReportStatus.WAITING_FOR_ERROR_REVIEW,ValidationReportMode.BEFORE_CONFIRMATION,COMPLETED,DECIDED,true,receipt,null,
                new ValidationReviewGate.Application(ValidationApplicationStatus.HELD_REVIEW,1,java.util.List.of(),null,1),ValidationExtractionState.SUCCEEDED));
        assertThat(held.allowedActions()).containsExactly(ValidationReviewAction.APPLY_SETTINGS);
        assertThat(completed.errorReviewStatus()).isEqualTo(ValidationPreparationStatus.READY);
        var invalidated=ValidationReviewGate.evaluate(new Input(true,ValidationPreparationStatus.INVALIDATED,ValidationReportStatus.INVALIDATED,ValidationReportMode.BEFORE_CONFIRMATION,COMPLETED,Counts.EMPTY,true,receipt));
        assertThat(invalidated.lockReason()).isEqualTo(ValidationReviewLockReason.REPORT_INVALIDATED);assertThat(invalidated.allowedActions()).containsExactly(ValidationReviewAction.RECHECK);
        var retired=ValidationReviewGate.evaluate(new Input(false,ValidationPreparationStatus.INVALIDATED,ValidationReportStatus.INVALIDATED,ValidationReportMode.BEFORE_CONFIRMATION,COMPLETED,DECIDED,true,receipt));
        assertThat(retired.completion()).as("retired receipts are history only").isNull();assertThat(retired.completionMode()).isEqualTo(ValidationReviewCompletionMode.BLOCKED);
    }

    @Test @DisplayName("#314 B2: 회차 전체 확정 APPLIED 현재 report는 재검수 회차 조건을 만족할 때만 RECHECK를 안내한다")
    void appliedReportOffersRecheckOnlyWhenEpisodeAdmits() {
        var receipt=new Completion(java.util.UUID.randomUUID(),ValidationReviewTrigger.AUTOMATIC,java.time.LocalDateTime.now(),java.util.UUID.randomUUID());
        var applied=new ValidationReviewGate.Application(ValidationApplicationStatus.APPLIED,1,java.util.List.of(),java.time.LocalDateTime.now(),1);
        var held=new ValidationReviewGate.Application(ValidationApplicationStatus.HELD_REVIEW,1,java.util.List.of(),null,1);
        java.util.function.BiFunction<ValidationReviewGate.Application,Boolean,java.util.List<ValidationReviewAction>> actions=(application,admitted)->ValidationReviewGate.evaluate(new Input(true,
                ValidationPreparationStatus.READY,ValidationReportStatus.WAITING_FOR_ERROR_REVIEW,ValidationReportMode.BEFORE_CONFIRMATION,COMPLETED,DECIDED,true,receipt,null,application,ValidationExtractionState.SUCCEEDED,admitted)).allowedActions();
        assertThat(actions.apply(applied,true)).containsExactly(ValidationReviewAction.RECHECK);
        assertThat(actions.apply(applied,false)).as("not the latest episode, analysis running, or source purge pending").isEmpty();
        assertThat(actions.apply(held,true)).as("only APPLIED is diagnostic; a held report still applies settings").containsExactly(ValidationReviewAction.APPLY_SETTINGS);
        var retired=ValidationReviewGate.evaluate(new Input(false,ValidationPreparationStatus.READY,ValidationReportStatus.WAITING_FOR_ERROR_REVIEW,ValidationReportMode.BEFORE_CONFIRMATION,COMPLETED,DECIDED,true,receipt,null,applied,ValidationExtractionState.SUCCEEDED,true));
        assertThat(retired.allowedActions()).as("a retired report is never rechecked").isEmpty();
    }

    @Test @DisplayName("분기마다 허용 판단을 분리한다")
    void allowedJudgmentsPerBranch() {
        assertThat(ValidationJudgmentValue.allowedFor(ValidationBranch.SETTING)).containsExactly(ValidationJudgmentValue.PENDING,ValidationJudgmentValue.NEEDS_FIX,ValidationJudgmentValue.NOT_CONFLICT);
        assertThat(ValidationJudgmentValue.allowedFor(ValidationBranch.TYPO)).containsExactly(ValidationJudgmentValue.PENDING,ValidationJudgmentValue.NEEDS_FIX,ValidationJudgmentValue.KEEP_ORIGINAL);
        assertThat(ValidationJudgmentValue.KEEP_ORIGINAL.allowedIn(ValidationBranch.SETTING)).isFalse();assertThat(ValidationJudgmentValue.NOT_CONFLICT.allowedIn(ValidationBranch.TYPO)).isFalse();
    }
}
