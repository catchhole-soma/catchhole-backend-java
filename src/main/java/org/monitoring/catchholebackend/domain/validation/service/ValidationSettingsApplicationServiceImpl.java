package org.monitoring.catchholebackend.domain.validation.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisAutomaticApplicationContributor;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationSettingsApplicationRequest;
import org.monitoring.catchholebackend.domain.validation.dto.response.*;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationReviewMapper;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationApplicationGate;
import org.monitoring.catchholebackend.domain.validation.repository.*;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import static org.monitoring.catchholebackend.domain.validation.processor.ValidationExecutionAccess.error;
import static org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode.*;

/**
 * Work 잠금 → (명시) report CAS → 평가 → READY면 기존 자동 반영 규칙(contributor)을 두 도메인 모두 실행하고 남은 후보가 없을 때만 APPLIED.
 * contributor가 일부 후보를 보류하거나 어떤 예외라도 나면 트랜잭션 전체를 되돌린 뒤 별도 트랜잭션에서 HELD_REVIEW와 사유를 기록한다.
 */
@Slf4j @Service @RequiredArgsConstructor
public class ValidationSettingsApplicationServiceImpl implements ValidationSettingsApplicationService {
    private static final List<String> DOMAINS=List.of("characters","worldSettings");
    private final PlatformTransactionManager transactions;
    private final WorkRepository works;
    private final ValidationReportRepository reports;
    private final ValidationSettingsApplicationRepository applications;
    private final ValidationApplicationGate gate;
    private final ValidationReviewMapper mapper;
    private final AnalysisStateJournal journal;
    private final List<AnalysisAutomaticApplicationContributor> contributors;
    private final EntityManager em;

    @Override @Transactional(readOnly=true) public ValidationApplicationGate.Decision evaluate(ValidationReport report) {return gate.evaluate(report);}
    @Override @Transactional(readOnly=true) public boolean isReleaseJob(AnalysisJob job) {return gate.releaseOf(job).isPresent();}

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void applyAutomatically(AnalysisJob job,AppException rolledBack) {
        // 호출자(/complete)가 이미 Work → Job을 잠그고 불변 기록을 SEALED로 만들었다.
        var completion=gate.releaseOf(job).orElseThrow(()->new IllegalStateException("검수 release Job이 아닙니다."));
        var receipt=receipt(completion);
        if(receipt.isApplied())return;
        if(rolledBack!=null) {hold(receipt,ValidationReviewTrigger.AUTOMATIC,null,null,reasons(rolledBack),errorCode(rolledBack));return;}
        var decision=gate.evaluate(completion.getReport());
        switch(decision.kind()) {
            case READY->applyPlan(receipt,decision,ValidationReviewTrigger.AUTOMATIC,null,null);
            case HELD->hold(receipt,ValidationReviewTrigger.AUTOMATIC,null,null,decision.reasons(),null);
            case NOT_READY->log.warn("SEALED 뒤에도 회차 전체 확정 평가가 준비되지 않았습니다. analysisJobId={}",job.getId());
        }
    }

    @Override @Transactional(propagation=Propagation.SUPPORTS)
    public ValidationSettingsApplicationResponse applySettings(Long memberId,UUID workId,UUID reportId,ValidationSettingsApplicationRequest request) {
        String hash=journal.hash(JsonNodeFactory.instance.objectNode().put("reportId",reportId.toString()).put("expectedReportVersion",request.expectedReportVersion()));
        // 바깥 트랜잭션에 참여하면 되돌림 뒤 재실행을 할 수 없으므로 신호를 그대로 전달한다.
        if(TransactionSynchronizationManager.isActualTransactionActive())return explicit(memberId,workId,reportId,request,hash,null);
        var tx=new TransactionTemplate(transactions);
        try {return tx.execute(s->explicit(memberId,workId,reportId,request,hash,null));}
        catch(AppException e) {
            if(!ValidationSettingsApplicationService.isRolledBack(e))throw e;
            log.warn("회차 전체 반영을 되돌리고 보류로 기록합니다. reportId={}, code={}",reportId,errorCode(e));
            return tx.execute(s->explicit(memberId,workId,reportId,request,hash,e));
        }
    }

    private ValidationSettingsApplicationResponse explicit(Long memberId,UUID workId,UUID reportId,ValidationSettingsApplicationRequest request,String hash,AppException rolledBack) {
        works.getOwnedWorkForUpdate(workId,memberId);
        var keyed=applications.findByWorkIdAndIdempotencyKey(workId,request.idempotencyKey());
        if(keyed.isPresent()) {
            if(!hash.equals(keyed.get().getRequestHash()))throw error(VALIDATION_IDEMPOTENCY_CONFLICT);
            return mapper.toApplicationResponse(keyed.get());
        }
        var r=reports.findByIdAndWorkId(reportId,workId).orElseThrow(()->error(VALIDATION_NOT_FOUND));em.flush();em.refresh(r);
        var completion=gate.completion(r).orElseThrow(()->error(VALIDATION_APPLICATION_UNAVAILABLE));
        var receipt=receipt(completion);
        // 이미 반영한 회차는 다른 키로 다시 요청해도 같은 receipt다. 새 시도·version 증가가 없다.
        if(receipt.isApplied())return mapper.toApplicationResponse(receipt);
        if(r.getVersion()!=request.expectedReportVersion())throw error(VALIDATION_REPORT_VERSION_CONFLICT);
        if(rolledBack!=null)return mapper.toApplicationResponse(hold(receipt,ValidationReviewTrigger.EXPLICIT,request.idempotencyKey(),hash,reasons(rolledBack),errorCode(rolledBack)));
        var decision=gate.evaluate(r);
        return mapper.toApplicationResponse(switch(decision.kind()) {
            case NOT_READY->throw error(VALIDATION_APPLICATION_NOT_READY);
            case HELD->hold(receipt,ValidationReviewTrigger.EXPLICIT,request.idempotencyKey(),hash,decision.reasons(),null);
            case READY->applyPlan(receipt,decision,ValidationReviewTrigger.EXPLICIT,request.idempotencyKey(),hash);
        });
    }

    /**
     * 캐릭터 → 세계관 순서로 같은 트랜잭션에서 도메인마다 작가 결정 그룹을 공개 그룹 확정 본문으로 먼저 반영하고 나머지를 기존 자동 반영 규칙으로 반영한다.
     * 확정된 후보는 검토 대기가 아니므로 자동 반영이 다시 반영하거나 보류하지 않는다. contributor의 검증 실패 흡수는 그대로지만
     * 실행 뒤 검토 대기 후보가 하나라도 남거나 예외가 나면 이 트랜잭션은 부분 반영이므로 VALIDATION_APPLICATION_ROLLED_BACK으로 전체를 되돌린다.
     * receipt는 도메인 쓰기 전에 APPLIED로 기록한다. 같은 트랜잭션이라 실패하면 함께 되돌려지고, 확정 설정 변경의 report 무효화는 APPLIED report를 건너뛴다.
     */
    private ValidationSettingsApplication applyPlan(ValidationSettingsApplication receipt,ValidationApplicationGate.Decision decision,ValidationReviewTrigger trigger,String key,String hash) {
        var job=decision.plan().job();List<ValidationApplicationHoldReason> left;
        var r=receipt.getReport();r.advanceVersion();receipt.apply(trigger,key,hash,r.getVersion());var saved=save(receipt);
        try {
            for(var contributor:orderedContributors()) {contributor.applyReviewedDecisions(job);contributor.applyAutomatically(job);}
            em.flush();left=gate.leftovers(job);
        } catch(AppException e) {
            if(ValidationSettingsApplicationService.isRolledBack(e))throw e;
            throw rolledBack(List.of(ValidationApplicationHoldReason.episode(ValidationApplicationHoldCode.UNKNOWN_ERROR,e.getResultCode().getCode())),e.getResultCode().getCode());
        } catch(RuntimeException e) {
            log.error("회차 전체 반영 중 예상하지 못한 오류로 되돌립니다. analysisJobId={}",job.getId(),e);
            throw rolledBack(List.of(ValidationApplicationHoldReason.episode(ValidationApplicationHoldCode.UNKNOWN_ERROR,"UNEXPECTED_ERROR")),"UNEXPECTED_ERROR");
        }
        if(!left.isEmpty())throw rolledBack(left,null);
        job.completeReviewReleaseApplication();em.flush();
        return saved;
    }
    /** 보류는 확정 설정을 쓰지 않는다. 추출이 SEALED면 release Job의 자동 단계를 끝내 작가가 후보를 직접 검토·결정할 수 있게 한다(automaticAppliedAt은 null). */
    private ValidationSettingsApplication hold(ValidationSettingsApplication receipt,ValidationReviewTrigger trigger,String key,String hash,List<ValidationApplicationHoldReason> reasons,String errorCode) {
        var r=receipt.getReport();r.advanceVersion();receipt.hold(trigger,key,hash,mapper.toHoldReasonTree(reasons),errorCode,r.getVersion());
        var job=receipt.getCompletion().getExtractionJob();
        if(job.getJournalStatus()==org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus.SEALED&&job.getAutomaticAppliedAt()==null
                &&(job.getStatus()==org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING||job.getStatus()==org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.SUCCEEDED))job.holdReviewReleaseApplication();
        return save(receipt);
    }
    private ValidationSettingsApplication save(ValidationSettingsApplication receipt) {var saved=receipt.getId()==null?applications.save(receipt):receipt;em.flush();return saved;}
    private ValidationSettingsApplication receipt(ValidationReviewCompletion completion) {
        return applications.findByReportId(completion.getReport().getId()).orElseGet(()->ValidationSettingsApplication.start(completion.getReport(),completion));
    }
    private List<AnalysisAutomaticApplicationContributor> orderedContributors() {
        var byDomain=new LinkedHashMap<String,AnalysisAutomaticApplicationContributor>();
        for(var c:contributors)if(byDomain.put(c.applicationDomain(),c)!=null)throw new IllegalStateException("자동 반영 도메인이 중복되었습니다.");
        if(!byDomain.keySet().equals(Set.copyOf(DOMAINS)))throw new IllegalStateException("캐릭터와 세계관 자동 반영이 모두 필요합니다.");
        return DOMAINS.stream().map(byDomain::get).toList();
    }
    private static AppException rolledBack(List<ValidationApplicationHoldReason> reasons,String errorCode) {
        var context=new LinkedHashMap<String,Object>();context.put("holdReasons",reasons);if(errorCode!=null)context.put("lastErrorCode",errorCode);
        return new AppException(VALIDATION_APPLICATION_ROLLED_BACK,context);
    }
    @SuppressWarnings("unchecked")
    private static List<ValidationApplicationHoldReason> reasons(AppException e) {
        var value=e.getErrorContext().get("holdReasons");
        return value instanceof List<?> list&&!list.isEmpty()?(List<ValidationApplicationHoldReason>)list:List.of(ValidationApplicationHoldReason.episode(ValidationApplicationHoldCode.UNKNOWN_ERROR,null));
    }
    private static String errorCode(AppException e) {var value=e.getErrorContext().get("lastErrorCode");return value==null?null:value.toString();}
}
