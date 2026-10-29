package org.monitoring.catchholebackend.domain.validation.processor;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.repository.*;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties;
import org.springframework.stereotype.Component;
import static org.monitoring.catchholebackend.domain.validation.type.ValidationExecutionStatus.*;
/**
 * 작가 판단 잠금과 검토 종료 경로 계산. 상태를 바꾸지 않는다.
 * GH312 S2의 ValidationReviewCompletionService가 이 판정으로 자동/명시 검토 종료를 허가하며, 현재 report의 receipt만 읽는다.
 */
@Component @RequiredArgsConstructor
public class ValidationReviewGate {
    private static final Set<ValidationExecutionStatus> JUDGEABLE=EnumSet.of(COMPLETED,PARTIAL,FAILED);
    private final ValidationJobRepository jobs;
    private final ValidationReviewRepository reviews;
    private final ValidationReviewCompletionRepository completions;
    private final ValidationExecutionProperties properties;

    public record Counts(long settingTotal,long settingPending,long settingNeedsFix,long settingNotConflict,long typoTotal,long typoPending,long typoNeedsFix,long typoKeepOriginal) {
        public static final Counts EMPTY=new Counts(0,0,0,0,0,0,0,0);
        public long total() {return settingTotal+typoTotal;}
        public long pending() {return settingPending+typoPending;}
        public long needsFix() {return settingNeedsFix+typoNeedsFix;}
    }
    /** 현재 report의 검토 종료 receipt 요약. */
    public record Completion(UUID completionId,ValidationReviewTrigger trigger,LocalDateTime completedAt,UUID extractionJobId) {}
    /** /complete의 자동 검토 종료가 되돌려진 이유와 시각. */
    public record ReleaseHold(String code,LocalDateTime at) {}
    public record Input(boolean current,ValidationPreparationStatus baselineStatus,ValidationReportStatus status,ValidationReportMode mode,ValidationExecutionStatus execution,Counts counts,boolean executionEnabled,Completion completion,ReleaseHold releaseHold) {
        public Input(boolean current,ValidationPreparationStatus baselineStatus,ValidationReportStatus status,ValidationReportMode mode,ValidationExecutionStatus execution,Counts counts,boolean executionEnabled) {
            this(current,baselineStatus,status,mode,execution,counts,executionEnabled,null,null);
        }
        public Input(boolean current,ValidationPreparationStatus baselineStatus,ValidationReportStatus status,ValidationReportMode mode,ValidationExecutionStatus execution,Counts counts,boolean executionEnabled,Completion completion) {
            this(current,baselineStatus,status,mode,execution,counts,executionEnabled,completion,null);
        }
    }
    public record Review(ValidationExecutionStatus executionStatus,Counts counts,ValidationReviewLockReason lockReason,ValidationReviewCompletionMode completionMode,
            List<ValidationReviewHoldReason> holdReasons,List<ValidationReviewAction> allowedActions,ValidationPreparationStatus errorReviewStatus,Completion completion,ReleaseHold releaseHold) {
        public boolean judgmentLocked() {return lockReason!=null;}
        /** 명시 검토 완료 endpoint가 받는 상태. AUTOMATIC_ELIGIBLE은 /complete의 자동 종료가 추출 생성 조건에 막혀 보류됐을 때만 남는다. */
        public boolean canCompleteReview() {return completionMode==ValidationReviewCompletionMode.EXPLICIT_REQUIRED||completionMode==ValidationReviewCompletionMode.AUTOMATIC_ELIGIBLE;}
    }
    /** ValidationStagingResponse의 추출 상태. 검토 종료 전에는 NOT_STARTED이며 후보 상태는 S3 범위다. */
    public record Extraction(UUID jobId,ValidationPreparationStatus status) {
        public static final Extraction NOT_STARTED=new Extraction(null,ValidationPreparationStatus.NOT_STARTED);
    }

    public Review evaluate(ValidationReport r) { var execution=execution(r);return evaluate(input(r,execution,counts(r.getId()),completion(r).orElse(null))); }
    /** 실행이 끝나기 전에는 판단 개수를 읽지 않는다. */
    public ValidationPreparationStatus errorReviewStatus(ValidationReport r) {
        var execution=execution(r);if(invalidated(r.isCurrent(),r.getBaselineStatus(),r.getStatus()))return ValidationPreparationStatus.INVALIDATED;
        if(execution!=COMPLETED)return ValidationPreparationStatus.NOT_STARTED;return evaluate(input(r,execution,counts(r.getId()),completion(r).orElse(null))).errorReviewStatus();
    }
    /** GH312: 퇴역한 report의 receipt는 이력일 뿐 검토 요약·추출 허가에 쓰지 않는다. */
    public Optional<Completion> completion(ValidationReport r) {
        if(!r.isCurrent())return Optional.empty();
        return completions.findByReportId(r.getId()).map(c->new Completion(c.getId(),c.getTrigger(),c.getCompletedAt(),c.getExtractionJob().getId()));
    }
    /** release Job 상태를 scalar로 읽는다. 진행 중 PENDING, 성공 READY, 실패·취소·재개가 필요한 미완료 기록 FAILED. */
    public Extraction extraction(ValidationReport r) {
        var c=completion(r).orElse(null);if(c==null)return Extraction.NOT_STARTED;
        var state=reviews.extractionJobState(c.extractionJobId()).orElse(null);
        if(state==null)return new Extraction(c.extractionJobId(),ValidationPreparationStatus.FAILED);
        var status=(AnalysisJobStatus)state[0];var journal=(AnalysisJournalStatus)state[1];
        return new Extraction(c.extractionJobId(),switch(status) {
            case PENDING,RUNNING->ValidationPreparationStatus.PENDING;
            case SUCCEEDED->journal==AnalysisJournalStatus.INCOMPLETE||journal==AnalysisJournalStatus.INVALIDATED?ValidationPreparationStatus.FAILED:ValidationPreparationStatus.READY;
            case FAILED,CANCELED->ValidationPreparationStatus.FAILED;
        });
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
        var completion=in.current()?in.completion():null;boolean completed=completion!=null;
        if(invalid)holds.add(ValidationReviewHoldReason.REPORT_INVALIDATED);
        else if(in.baselineStatus()!=ValidationPreparationStatus.READY||in.status()!=ValidationReportStatus.WAITING_FOR_ERROR_REVIEW)holds.add(ValidationReviewHoldReason.BASELINE_NOT_READY);
        if(in.execution()!=COMPLETED)holds.add(ValidationReviewHoldReason.EXECUTION_NOT_COMPLETED);
        if(counts.pending()>0)holds.add(ValidationReviewHoldReason.PENDING_JUDGMENTS);
        if(counts.needsFix()>0)holds.add(ValidationReviewHoldReason.NEEDS_FIX_PENDING);
        if(in.mode()==ValidationReportMode.DIAGNOSTIC_ONLY)holds.add(ValidationReviewHoldReason.DIAGNOSTIC_ONLY);
        var mode=completed?ValidationReviewCompletionMode.COMPLETED:!holds.isEmpty()?ValidationReviewCompletionMode.BLOCKED
                :counts.total()==0?ValidationReviewCompletionMode.AUTOMATIC_ELIGIBLE:ValidationReviewCompletionMode.EXPLICIT_REQUIRED;
        // 자동 종료 보류는 경로를 막지 않는 안내다. 판정 뒤에 붙여 명시 검토 완료(COMPLETE_REVIEW)를 그대로 연다.
        var releaseHold=in.current()&&!completed?in.releaseHold():null;if(releaseHold!=null)holds.add(ValidationReviewHoldReason.RELEASE_HELD);
        var lock=lockReason(in.current(),in.baselineStatus(),in.status(),in.execution(),completed);var actions=new ArrayList<ValidationReviewAction>();
        if(lock==null&&counts.total()>0)actions.add(ValidationReviewAction.SAVE_JUDGMENT);
        if(mode==ValidationReviewCompletionMode.EXPLICIT_REQUIRED||mode==ValidationReviewCompletionMode.AUTOMATIC_ELIGIBLE)actions.add(ValidationReviewAction.COMPLETE_REVIEW);
        if(in.executionEnabled()&&!invalid&&!completed&&(in.execution()==PARTIAL||in.execution()==FAILED||in.execution()==NOT_STARTED&&in.baselineStatus()==ValidationPreparationStatus.READY))actions.add(ValidationReviewAction.RETRY);
        if(in.current()&&invalid)actions.add(ValidationReviewAction.RECHECK);
        var status=invalid?ValidationPreparationStatus.INVALIDATED:in.execution()!=COMPLETED?ValidationPreparationStatus.NOT_STARTED
                :mode!=ValidationReviewCompletionMode.BLOCKED?ValidationPreparationStatus.READY:ValidationPreparationStatus.PENDING;
        return new Review(in.execution(),counts,lock,mode,List.copyOf(holds),List.copyOf(actions),status,completion,releaseHold);
    }
    /** 판단 저장은 현재 READY 기준의 실행 종료·중단 뒤, 검토 종료 전에만 가능하다. 재시도 queue/실행 중에는 다시 읽기 전용이다. */
    public static ValidationReviewLockReason lockReason(boolean current,ValidationPreparationStatus baseline,ValidationReportStatus status,ValidationExecutionStatus execution,boolean reviewCompleted) {
        if(invalidated(current,baseline,status))return ValidationReviewLockReason.REPORT_INVALIDATED;
        if(baseline!=ValidationPreparationStatus.READY||status!=ValidationReportStatus.WAITING_FOR_ERROR_REVIEW)return ValidationReviewLockReason.BASELINE_NOT_READY;
        if(reviewCompleted)return ValidationReviewLockReason.REVIEW_COMPLETED;
        if(JUDGEABLE.contains(execution))return null;
        return execution==NOT_STARTED?ValidationReviewLockReason.EXECUTION_NOT_STARTED:execution==CANCELED?ValidationReviewLockReason.EXECUTION_CANCELED:ValidationReviewLockReason.EXECUTION_IN_PROGRESS;
    }
    public ValidationExecutionStatus execution(ValidationReport r) { return jobs.findByReportId(r.getId()).map(ValidationJob::getStatus).orElse(NOT_STARTED); }
    private Input input(ValidationReport r,ValidationExecutionStatus execution,Counts counts,Completion completion) {
        var hold=r.getReleaseHoldCode()==null?null:new ReleaseHold(r.getReleaseHoldCode(),r.getReleaseHoldAt());
        return new Input(r.isCurrent(),r.getBaselineStatus(),r.getStatus(),r.getMode(),execution,counts,properties.enabled(),completion,hold);
    }
    private static boolean invalidated(boolean current,ValidationPreparationStatus baseline,ValidationReportStatus status) { return !current||status==ValidationReportStatus.INVALIDATED||baseline==ValidationPreparationStatus.INVALIDATED; }
}
