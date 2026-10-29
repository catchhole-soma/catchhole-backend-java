package org.monitoring.catchholebackend.domain.validation.processor;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.repository.*;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties;
import org.springframework.stereotype.Component;
import static org.monitoring.catchholebackend.domain.validation.type.ValidationExecutionStatus.*;
/**
 * 작가 판단 잠금과 검토 종료 경로 계산. 상태 변경이나 추출 release를 하지 않는다.
 * GH312 S1은 COMPLETE_REVIEW 가능 여부만 알리고 실제 검토 완료·추출 허가는 S2가 이 판정을 다시 호출해 수행한다.
 */
@Component @RequiredArgsConstructor
public class ValidationReviewGate {
    private static final Set<ValidationExecutionStatus> JUDGEABLE=EnumSet.of(COMPLETED,PARTIAL,FAILED);
    private final ValidationJobRepository jobs;
    private final ValidationReviewRepository reviews;
    private final ValidationExecutionProperties properties;

    public record Counts(long settingTotal,long settingPending,long settingNeedsFix,long settingNotConflict,long typoTotal,long typoPending,long typoNeedsFix,long typoKeepOriginal) {
        public static final Counts EMPTY=new Counts(0,0,0,0,0,0,0,0);
        public long total() {return settingTotal+typoTotal;}
        public long pending() {return settingPending+typoPending;}
        public long needsFix() {return settingNeedsFix+typoNeedsFix;}
    }
    public record Input(boolean current,ValidationPreparationStatus baselineStatus,ValidationReportStatus status,ValidationReportMode mode,ValidationExecutionStatus execution,Counts counts,boolean executionEnabled) {}
    public record Review(ValidationExecutionStatus executionStatus,Counts counts,ValidationReviewLockReason lockReason,ValidationReviewCompletionMode completionMode,
            List<ValidationReviewHoldReason> holdReasons,List<ValidationReviewAction> allowedActions,ValidationPreparationStatus errorReviewStatus) {
        public boolean judgmentLocked() {return lockReason!=null;}
        public boolean canCompleteReview() {return completionMode==ValidationReviewCompletionMode.EXPLICIT_REQUIRED;}
    }

    public Review evaluate(ValidationReport r) { var execution=execution(r);return evaluate(input(r,execution,counts(r.getId()))); }
    /** 실행이 끝나기 전에는 판단 개수를 읽지 않는다. */
    public ValidationPreparationStatus errorReviewStatus(ValidationReport r) {
        var execution=execution(r);if(invalidated(r.isCurrent(),r.getBaselineStatus(),r.getStatus()))return ValidationPreparationStatus.INVALIDATED;
        if(execution!=COMPLETED)return ValidationPreparationStatus.NOT_STARTED;return evaluate(input(r,execution,counts(r.getId()))).errorReviewStatus();
    }
    public Counts counts(UUID report) {
        long[] c=new long[8];
        for(var row:reviews.currentJudgmentCounts(report)) {
            int base=row.branch()==ValidationBranch.SETTING?0:4;c[base]+=row.count();
            switch(row.judgment()) {case PENDING->c[base+1]+=row.count();case NEEDS_FIX->c[base+2]+=row.count();case NOT_CONFLICT,KEEP_ORIGINAL->c[base+3]+=row.count();}
        }
        return new Counts(c[0],c[1],c[2],c[3],c[4],c[5],c[6],c[7]);
    }
    public static Review evaluate(Input in) {
        boolean invalid=invalidated(in.current(),in.baselineStatus(),in.status());var counts=in.counts();var holds=new ArrayList<ValidationReviewHoldReason>();
        if(invalid)holds.add(ValidationReviewHoldReason.REPORT_INVALIDATED);
        else if(in.baselineStatus()!=ValidationPreparationStatus.READY||in.status()!=ValidationReportStatus.WAITING_FOR_ERROR_REVIEW)holds.add(ValidationReviewHoldReason.BASELINE_NOT_READY);
        if(in.execution()!=COMPLETED)holds.add(ValidationReviewHoldReason.EXECUTION_NOT_COMPLETED);
        if(counts.pending()>0)holds.add(ValidationReviewHoldReason.PENDING_JUDGMENTS);
        if(counts.needsFix()>0)holds.add(ValidationReviewHoldReason.NEEDS_FIX_PENDING);
        if(in.mode()==ValidationReportMode.DIAGNOSTIC_ONLY)holds.add(ValidationReviewHoldReason.DIAGNOSTIC_ONLY);
        var mode=!holds.isEmpty()?ValidationReviewCompletionMode.BLOCKED:counts.total()==0?ValidationReviewCompletionMode.AUTOMATIC_ELIGIBLE:ValidationReviewCompletionMode.EXPLICIT_REQUIRED;
        var lock=lockReason(in.current(),in.baselineStatus(),in.status(),in.execution());var actions=new ArrayList<ValidationReviewAction>();
        if(lock==null&&counts.total()>0)actions.add(ValidationReviewAction.SAVE_JUDGMENT);
        if(mode==ValidationReviewCompletionMode.EXPLICIT_REQUIRED)actions.add(ValidationReviewAction.COMPLETE_REVIEW);
        if(in.executionEnabled()&&!invalid&&(in.execution()==PARTIAL||in.execution()==FAILED||in.execution()==NOT_STARTED&&in.baselineStatus()==ValidationPreparationStatus.READY))actions.add(ValidationReviewAction.RETRY);
        if(in.current()&&invalid)actions.add(ValidationReviewAction.RECHECK);
        var status=invalid?ValidationPreparationStatus.INVALIDATED:in.execution()!=COMPLETED?ValidationPreparationStatus.NOT_STARTED
                :mode!=ValidationReviewCompletionMode.BLOCKED?ValidationPreparationStatus.READY:ValidationPreparationStatus.PENDING;
        return new Review(in.execution(),counts,lock,mode,List.copyOf(holds),List.copyOf(actions),status);
    }
    /** 판단 저장은 현재 READY 기준의 실행 종료·중단 뒤에만 가능하다. 재시도 queue/실행 중에는 다시 읽기 전용이다. */
    public static ValidationReviewLockReason lockReason(boolean current,ValidationPreparationStatus baseline,ValidationReportStatus status,ValidationExecutionStatus execution) {
        if(invalidated(current,baseline,status))return ValidationReviewLockReason.REPORT_INVALIDATED;
        if(baseline!=ValidationPreparationStatus.READY||status!=ValidationReportStatus.WAITING_FOR_ERROR_REVIEW)return ValidationReviewLockReason.BASELINE_NOT_READY;
        if(JUDGEABLE.contains(execution))return null;
        return execution==NOT_STARTED?ValidationReviewLockReason.EXECUTION_NOT_STARTED:execution==CANCELED?ValidationReviewLockReason.EXECUTION_CANCELED:ValidationReviewLockReason.EXECUTION_IN_PROGRESS;
    }
    public ValidationExecutionStatus execution(ValidationReport r) { return jobs.findByReportId(r.getId()).map(ValidationJob::getStatus).orElse(NOT_STARTED); }
    private Input input(ValidationReport r,ValidationExecutionStatus execution,Counts counts) { return new Input(r.isCurrent(),r.getBaselineStatus(),r.getStatus(),r.getMode(),execution,counts,properties.enabled()); }
    private static boolean invalidated(boolean current,ValidationPreparationStatus baseline,ValidationReportStatus status) { return !current||status==ValidationReportStatus.INVALIDATED||baseline==ValidationPreparationStatus.INVALIDATED; }
}
