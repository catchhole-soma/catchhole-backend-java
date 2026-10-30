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
    private final ValidationSettingsApplicationRepository applications;
    private final org.monitoring.catchholebackend.domain.validation.mapper.ValidationReviewMapper mapper;
    private final ValidationExecutionProperties properties;
    private final ValidationRecheckAdmission recheckAdmission;

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
    /** 현재 report의 회차 전체 확정 receipt 요약(GH312 S3). 시도 전에는 null이다. */
    public record Application(ValidationApplicationStatus status,long attempt,List<org.monitoring.catchholebackend.domain.validation.dto.response.ValidationApplicationHoldReason> holdReasons,LocalDateTime appliedAt,long extractionJobGeneration) {}
    /**
     * appliedRecheckAdmitted(#314 B2): 회차 전체 확정 APPLIED인 현재 report의 회차가 ValidationRecheckAdmission 조건(최신 활성 회차·원문 파기 대기 없음·진행 중 추출/비교 없음·현재 batch)을
     * 만족하는지. 서버가 같은 원고의 진단 재검수를 받는 조건과 같으며 APPLIED가 아니면 계산하지 않는다(false).
     */
    public record Input(boolean current,ValidationPreparationStatus baselineStatus,ValidationReportStatus status,ValidationReportMode mode,ValidationExecutionStatus execution,Counts counts,boolean executionEnabled,Completion completion,ReleaseHold releaseHold,
            Application application,ValidationExtractionState extractionState,boolean appliedRecheckAdmitted) {
        public Input(boolean current,ValidationPreparationStatus baselineStatus,ValidationReportStatus status,ValidationReportMode mode,ValidationExecutionStatus execution,Counts counts,boolean executionEnabled) {
            this(current,baselineStatus,status,mode,execution,counts,executionEnabled,null,null);
        }
        public Input(boolean current,ValidationPreparationStatus baselineStatus,ValidationReportStatus status,ValidationReportMode mode,ValidationExecutionStatus execution,Counts counts,boolean executionEnabled,Completion completion) {
            this(current,baselineStatus,status,mode,execution,counts,executionEnabled,completion,null);
        }
        public Input(boolean current,ValidationPreparationStatus baselineStatus,ValidationReportStatus status,ValidationReportMode mode,ValidationExecutionStatus execution,Counts counts,boolean executionEnabled,Completion completion,ReleaseHold releaseHold) {
            this(current,baselineStatus,status,mode,execution,counts,executionEnabled,completion,releaseHold,null,ValidationExtractionState.NOT_STARTED);
        }
        public Input(boolean current,ValidationPreparationStatus baselineStatus,ValidationReportStatus status,ValidationReportMode mode,ValidationExecutionStatus execution,Counts counts,boolean executionEnabled,Completion completion,ReleaseHold releaseHold,
                Application application,ValidationExtractionState extractionState) {
            this(current,baselineStatus,status,mode,execution,counts,executionEnabled,completion,releaseHold,application,extractionState,false);
        }
    }
    public record Review(ValidationExecutionStatus executionStatus,Counts counts,ValidationReviewLockReason lockReason,ValidationReviewCompletionMode completionMode,
            List<ValidationReviewHoldReason> holdReasons,List<ValidationReviewAction> allowedActions,ValidationPreparationStatus errorReviewStatus,Completion completion,ReleaseHold releaseHold,
            Application application,ValidationExtractionState extractionState,ValidationReviewStage reviewStage) {
        public boolean judgmentLocked() {return lockReason!=null;}
        /** 명시 검토 완료 endpoint가 받는 상태. AUTOMATIC_ELIGIBLE은 /complete의 자동 종료가 추출 생성 조건에 막혀 보류됐을 때만 남는다. */
        public boolean canCompleteReview() {return completionMode==ValidationReviewCompletionMode.EXPLICIT_REQUIRED||completionMode==ValidationReviewCompletionMode.AUTOMATIC_ELIGIBLE;}
    }
    /**
     * ValidationStagingResponse의 추출·후보 상태와 /review의 extractionState. 검토 종료 전에는 모두 NOT_STARTED다.
     * candidateStatus(GH312 S3): 회차 전체 확정 APPLIED → READY, HELD_REVIEW → PENDING. receipt가 없으면 release Job 대기(PENDING) NOT_STARTED,
     * 실행 중이거나 SEALED 성공 뒤 평가 전 PENDING, 실패·취소·재개 필요 기록 FAILED.
     */
    public record Extraction(UUID jobId,ValidationPreparationStatus status,ValidationExtractionState state,ValidationPreparationStatus candidateStatus) {
        public static final Extraction NOT_STARTED=new Extraction(null,ValidationPreparationStatus.NOT_STARTED,ValidationExtractionState.NOT_STARTED,ValidationPreparationStatus.NOT_STARTED);
    }

    public Review evaluate(ValidationReport r) {
        var execution=execution(r);var completion=completion(r).orElse(null);var application=completion==null?null:application(r).orElse(null);
        // #314 B2: 회차 조건 조회는 APPLIED 현재 report에서만 한다. 재검수 요청과 같은 ValidationRecheckAdmission 판정이다.
        boolean recheck=application!=null&&application.status()==ValidationApplicationStatus.APPLIED&&!invalidated(r.isCurrent(),r.getBaselineStatus(),r.getStatus())&&recheckAdmission.admitsEpisode(r);
        return evaluate(input(r,execution,counts(r.getId()),completion,application,completion==null?ValidationExtractionState.NOT_STARTED:extraction(r).state(),recheck));
    }
    /** GH312 S3: 현재 report의 회차 전체 확정 receipt. 퇴역 report의 receipt는 읽지 않는다. */
    public Optional<Application> application(ValidationReport r) {
        if(!r.isCurrent())return Optional.empty();
        return applications.findByReportId(r.getId()).map(a->new Application(a.getStatus(),a.getAttempt(),mapper.toHoldReasons(a.getHoldReasons()),a.getAppliedAt(),a.getExtractionJobGeneration()));
    }
    /** 실행이 끝나기 전에는 판단 개수를 읽지 않는다. */
    public ValidationPreparationStatus errorReviewStatus(ValidationReport r) {
        var execution=execution(r);if(invalidated(r.isCurrent(),r.getBaselineStatus(),r.getStatus()))return ValidationPreparationStatus.INVALIDATED;
        if(execution!=COMPLETED)return ValidationPreparationStatus.NOT_STARTED;return evaluate(input(r,execution,counts(r.getId()),completion(r).orElse(null),null,ValidationExtractionState.NOT_STARTED,false)).errorReviewStatus();
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
        var application=application(r).map(Application::status).orElse(null);
        if(state==null)return new Extraction(c.extractionJobId(),ValidationPreparationStatus.FAILED,ValidationExtractionState.FAILED,candidates(application,ValidationPreparationStatus.FAILED));
        var status=(AnalysisJobStatus)state[0];var extractionState=ValidationReviewStages.extractionState(status,(AnalysisJournalStatus)state[1]);
        var extraction=switch(extractionState) {case RUNNING->ValidationPreparationStatus.PENDING;case SUCCEEDED->ValidationPreparationStatus.READY;default->ValidationPreparationStatus.FAILED;};
        return new Extraction(c.extractionJobId(),extraction,extractionState,
                application==null&&status==AnalysisJobStatus.PENDING?ValidationPreparationStatus.NOT_STARTED:candidates(application,extraction));
    }
    private static ValidationPreparationStatus candidates(ValidationApplicationStatus application,ValidationPreparationStatus extraction) {
        if(application==ValidationApplicationStatus.APPLIED)return ValidationPreparationStatus.READY;
        if(application!=null)return ValidationPreparationStatus.PENDING;
        return extraction==ValidationPreparationStatus.FAILED?ValidationPreparationStatus.FAILED:ValidationPreparationStatus.PENDING;
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
        var application=completed?in.application():null;
        // 무효 현재 report는 재검수 안내(실제 회차 조건은 요청이 검사). #314 B2: 회차 전체 확정 APPLIED 현재 report는 서버가 진단 재검수를 받는 회차 조건일 때만 안내한다.
        if(in.current()&&invalid)actions.add(ValidationReviewAction.RECHECK);
        else if(in.current()&&application!=null&&application.status()==ValidationApplicationStatus.APPLIED&&in.appliedRecheckAdmitted())actions.add(ValidationReviewAction.RECHECK);
        // GH312 S3: 검토 종료 뒤 아직 반영하지 않은 유효 report만 안내한다. 무효 report의 반영은 REPORT_INVALIDATED로 보류될 뿐이므로 재검수를 안내한다.
        if(completed&&!invalid&&(application==null||application.status()!=ValidationApplicationStatus.APPLIED))actions.add(ValidationReviewAction.APPLY_SETTINGS);
        var status=invalid?ValidationPreparationStatus.INVALIDATED:in.execution()!=COMPLETED?ValidationPreparationStatus.NOT_STARTED
                :mode!=ValidationReviewCompletionMode.BLOCKED?ValidationPreparationStatus.READY:ValidationPreparationStatus.PENDING;
        var extractionState=completed&&in.extractionState()!=null?in.extractionState():ValidationExtractionState.NOT_STARTED;
        var stage=ValidationReviewStages.resolve(new ValidationReviewStages.Input(in.current(),in.status(),in.baselineStatus(),in.execution(),completed,extractionState,application==null?null:application.status()));
        return new Review(in.execution(),counts,lock,mode,List.copyOf(holds),List.copyOf(actions),status,completion,releaseHold,application,extractionState,stage);
    }
    /** GH312 S4: 단건 응답(ValidationStagingResponse)의 검수 단계. 목록 projection과 같은 {@link ValidationReviewStages#resolve}를 쓴다. */
    public ValidationReviewStage reviewStage(ValidationReport r) {
        var completion=completion(r).orElse(null);
        var extraction=completion==null?ValidationExtractionState.NOT_STARTED:extraction(r).state();
        var application=completion==null?null:application(r).map(Application::status).orElse(null);
        return ValidationReviewStages.resolve(new ValidationReviewStages.Input(r.isCurrent(),r.getStatus(),r.getBaselineStatus(),execution(r),completion!=null,extraction,application));
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
    private Input input(ValidationReport r,ValidationExecutionStatus execution,Counts counts,Completion completion,Application application,ValidationExtractionState extractionState,boolean appliedRecheckAdmitted) {
        var hold=r.getReleaseHoldCode()==null?null:new ReleaseHold(r.getReleaseHoldCode(),r.getReleaseHoldAt());
        return new Input(r.isCurrent(),r.getBaselineStatus(),r.getStatus(),r.getMode(),execution,counts,properties.enabled(),completion,hold,application,extractionState,appliedRecheckAdmitted);
    }
    private static boolean invalidated(boolean current,ValidationPreparationStatus baseline,ValidationReportStatus status) { return !current||status==ValidationReportStatus.INVALIDATED||baseline==ValidationPreparationStatus.INVALIDATED; }
}
