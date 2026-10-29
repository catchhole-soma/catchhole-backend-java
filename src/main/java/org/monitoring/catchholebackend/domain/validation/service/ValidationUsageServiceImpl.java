package org.monitoring.catchholebackend.domain.validation.service;

import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.aitoken.entity.*;
import org.monitoring.catchholebackend.domain.aitoken.repository.*;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.aitoken.type.*;
import org.monitoring.catchholebackend.domain.validation.dto.ValidationExecutionContract.*;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationJob;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationExecutionAccess;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static org.monitoring.catchholebackend.domain.validation.processor.ValidationExecutionAccess.error;
import static org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode.*;

/** Real ValidationJob ownership; ledger settlement is separate from current publication authority. */
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class ValidationUsageServiceImpl implements ValidationUsageService {
    private final ValidationExecutionAccess access;
    private final AiTokenUsageRepository usages;
    private final AiTokenAccountRepository accounts;
    private final AiTokenService accountService;
    private final org.monitoring.catchholebackend.domain.validation.repository.ValidationExecutionRepository results;

    @Transactional public Map<String,Object> reserveValidationUsage(UUID id,UUID lease,Reserve command) {
        var job=access.running(id,lease,command.scope());
        var existing=usages.findByRequestId(command.requestId()).orElse(null);
        if(existing!=null) {
            owned(existing,job,lease,command.scope());
            if(existing.getPurpose()!=command.role().purpose()||!existing.getModelName().equals(command.model())||existing.getReservedTokens()!=command.reservedTokens())throw error(VALIDATION_PUBLICATION_CONFLICT);
            return view(existing);
        }
        var role=command.role();var limits=job.getConfiguration().path("budgets").path("roles").path(role.name());
        String model=job.getConfiguration().path("models").path(role.name()).path("model").asText();
        if(limits.isMissingNode()||!model.equals(command.model())||command.reservedTokens()<1)throw error(VALIDATION_RESULT_INVALID);
        var prior=billableCalls(job,role);
        long allocated=allocated(prior);
        if(prior.size()>=limits.path("maxCalls").asInt()||command.reservedTokens()>limits.path("maxReservedTokens").asLong()-allocated)throw error(VALIDATION_EXECUTION_LIMIT_EXCEEDED);
        var perCall=job.getConfiguration().path("budgets").path(role==ValidationRole.TYPO?"typo":"setting");
        if(role!=ValidationRole.QUERY_EMBEDDING&&command.reservedTokens()>(long)perCall.path("inputTokens").asInt()+perCall.path("outputTokens").asInt())throw error(VALIDATION_EXECUTION_LIMIT_EXCEEDED);
        var usage=AiTokenUsage.reserveValidation(command.requestId(),job,role.purpose(),command.model(),command.reservedTokens());
        account(job).reserve(usage.getReservedCharge());usages.save(usage);return view(usage);
    }
    @Transactional public Map<String,Object> reportValidationUsage(UUID id,UUID lease,UUID request,Usage command) {
        var job=access.lock(id);var usage=usages.findByRequestId(request).orElseThrow(()->error(VALIDATION_NOT_FOUND));
        owned(usage,job,lease,command.scope());
        if(command.kind()==UsageKind.SETTLE) {
            if(command.inputTokens()==null||command.cachedInputTokens()==null||command.outputTokens()==null||command.outcome()==null)throw error(VALIDATION_RESULT_INVALID);
            if(usage.getStatus()==AiTokenUsageStatus.SETTLED) {
                if(!Objects.equals(usage.getInputTokens(),command.inputTokens())||!Objects.equals(usage.getCachedInputTokens(),command.cachedInputTokens())
                        ||!Objects.equals(usage.getOutputTokens(),command.outputTokens())||usage.getOutcome()!=command.outcome())throw error(VALIDATION_PUBLICATION_CONFLICT);
            } else {
                var held=usage.getStatus()==AiTokenUsageStatus.RESERVED?usage.getReservedCharge():BigDecimal.ZERO;
                usage.settleValidation(command.inputTokens(),command.cachedInputTokens(),command.outputTokens(),command.outcome());
                account(job).settle(held,usage.getChargedTokens());
            }
        } else if(command.kind()==UsageKind.RELEASE) {
            if(command.inputTokens()!=null||command.cachedInputTokens()!=null||command.outputTokens()!=null||command.outcome()!=null)throw error(VALIDATION_RESULT_INVALID);
            if(usage.getStatus()==AiTokenUsageStatus.RESERVED) {
                // RELEASE certifies the provider was never called. Lost/ambiguous responses use UNKNOWN.
                access.require(job,lease,command.scope());account(job).release(usage.getReservedCharge());usage.release(AiTokenUsageOutcome.USAGE_UNAVAILABLE);
            } else if(usage.getStatus()!=AiTokenUsageStatus.RELEASED)throw error(VALIDATION_PUBLICATION_CONFLICT);
        } else {
            if(command.inputTokens()!=null||command.cachedInputTokens()!=null||command.outputTokens()!=null||command.outcome()!=null)throw error(VALIDATION_RESULT_INVALID);
            if(usage.getStatus()==AiTokenUsageStatus.RESERVED) {account(job).release(usage.getReservedCharge());usage.markValidationUnknown(AiTokenUsageOutcome.USAGE_UNAVAILABLE);}
            else if(usage.getStatus()!=AiTokenUsageStatus.UNKNOWN)throw error(VALIDATION_PUBLICATION_CONFLICT);
        }
        return view(usage);
    }
    /** Called only after Work -> Job has been locked, including invalidation and physical-purge admission. */
    @Transactional public void releaseReservedValidationUsage(ValidationJob job,AiTokenUsageOutcome reason) {
        for(var u:usages.findAllByValidationJobId(job.getId()))if(u.getStatus()==AiTokenUsageStatus.RESERVED) {
            account(job).release(u.getReservedCharge());u.markValidationUnknown(reason);
        }
    }
    public void ensureValidationRetryCapacity(ValidationJob job) {
        var needed=java.util.EnumSet.noneOf(ValidationRole.class);var stages=results.stages(job.getReport().getId());
        if(job.getRequiredGrid()==null||!job.getRequiredGrid().has("SETTING")) {
            needed.add(ValidationRole.PLANNER);needed.add(ValidationRole.COVERAGE);
            if(!job.getReport().getBaselineEntries().isEmpty())needed.add(ValidationRole.RELATED_SELECTION);
        }
        if(job.getRequiredGrid()==null||!job.getRequiredGrid().has("TYPO"))needed.add(ValidationRole.TYPO);
        if(job.getRequiredGrid()!=null)for(var branch:ValidationBranch.values())for(var row:job.getRequiredGrid().path(branch.name()).path("units"))
            if(stages.stream().noneMatch(s->s.getBranch()==branch&&s.isSuccessful()&&s.getStage().name().equals(row.path("stage").asText())&&s.getRef().equals(row.path("ref").asText()))) {
                switch(row.path("stage").asText()) {
                    case "PLANNER" -> needed.add(ValidationRole.PLANNER);
                    case "RELATED_SELECTION" -> needed.add(ValidationRole.RELATED_SELECTION);
                    case "COVERAGE","BOOK_COVERAGE" -> needed.add(ValidationRole.COVERAGE);
                    case "TYPO_SEGMENT" -> needed.add(ValidationRole.TYPO);
                    default -> { }
                }
            }
        if(results.checks(job.getReport().getId()).stream().anyMatch(c->!c.completed()))needed.add(ValidationRole.JUDGE);
        for(var role:needed) {
            var calls=billableCalls(job,role);var cap=job.getConfiguration().path("budgets").path("roles").path(role.name());
            if(calls.size()>=cap.path("maxCalls").asInt()||allocated(calls)>=cap.path("maxReservedTokens").asLong())throw error(VALIDATION_EXECUTION_LIMIT_EXCEEDED);
        }
    }
    private List<AiTokenUsage> billableCalls(ValidationJob job,ValidationRole role) {
        return usages.findAllByValidationJobId(job.getId()).stream().filter(u->u.getPurpose()==role.purpose()&&u.getStatus()!=AiTokenUsageStatus.RELEASED).toList();
    }
    private long allocated(List<AiTokenUsage> calls) {
        long total=0;for(var u:calls)total=Math.addExact(total,Math.max(u.getReservedTokens(),u.getStatus()==AiTokenUsageStatus.SETTLED?Math.addExact(u.getInputTokens(),u.getOutputTokens()):0));return total;
    }
    public List<Map<String,Object>> getValidationUsage(UUID id) {return usages.findAllByValidationJobId(id).stream().map(this::view).toList();}
    private void owned(AiTokenUsage u,ValidationJob job,UUID lease,Scope scope) {
        if(scope==null||scope.contractVersion()!=1||u.getValidationJob()==null||!u.getValidationJob().getId().equals(job.getId())||lease==null||!lease.equals(u.getValidationLeaseToken())
                ||!Objects.equals(u.getValidationGeneration(),scope.generation())||!job.getReport().getId().equals(scope.reportId())||!job.getInputDigest().equals(scope.inputDigest()))throw error(VALIDATION_LEASE_CONFLICT);
    }
    private AiTokenAccount account(ValidationJob job) {
        Long member=job.getReport().getMember().getId();var a=accounts.findByMemberId(member).orElse(null);
        if(a==null) {accountService.getUsage(member);a=accounts.findByMemberId(member).orElseThrow();}return a;
    }
    private Map<String,Object> view(AiTokenUsage u) {
        var m=new LinkedHashMap<String,Object>();m.put("requestId",u.getRequestId());m.put("role",u.getPurpose().name().substring("VALIDATION_".length()));
        m.put("attempt",u.getAttempt());m.put("generation",u.getValidationGeneration());m.put("model",u.getModelName());m.put("reservedTokens",u.getReservedTokens());
        m.put("status",u.getStatus());m.put("outcome",u.getOutcome());m.put("inputTokens",u.getInputTokens());m.put("cachedInputTokens",u.getCachedInputTokens());m.put("outputTokens",u.getOutputTokens());return m;
    }
}
