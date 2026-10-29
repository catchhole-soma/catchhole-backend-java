package org.monitoring.catchholebackend.domain.validation.service;
import com.fasterxml.jackson.databind.node.*;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;
import org.monitoring.catchholebackend.domain.validation.dto.ValidationExecutionContract.*;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationStagingResponse;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationExecutionMapper;
import org.monitoring.catchholebackend.domain.validation.processor.*;
import org.monitoring.catchholebackend.domain.validation.repository.*;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static org.monitoring.catchholebackend.domain.validation.processor.ValidationExecutionAccess.error;
import static org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode.*;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class ValidationWorkerServiceImpl implements ValidationWorkerService {
    private final ValidationJobRepository jobs;
    private final ValidationReportRepository reports;
    private final ValidationRequestKeyRepository keys;
    private final WorkRepository works;
    private final org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository episodes;
    private final ValidationExecutionAccess access;
    private final ValidationUsageService usage;
    private final ValidationExecutionMapper mapper;
    private final ValidationExecutionSnapshot snapshots;
    private final ValidationExecutionBudgetPolicy budgetPolicy;
    private final AnalysisStateJournal journal;
    private final ValidationExecutionProperties properties;
    private final org.monitoring.catchholebackend.global.config.chat.ChatProperties retrieval;
    private final EntityManager em;
    private final ValidationDataService data;
    private final ValidationBaselineService baselines;
    private final ValidationReviewGate reviewGate;

    @Override @Transactional public void queuePrepared(ValidationReport r) {
        if(!properties.enabled()||!r.isCurrent()||r.getBaselineStatus()!=ValidationPreparationStatus.READY||r.getBaselineSeed()==null||r.getBaselineEntries()==null||jobs.findByReportId(r.getId()).isPresent())return;
        access.current(r);data.validateSavedValidationSeed(r.getMember().getId(),r.getWork().getId(),r.getId());
        String manifest=journal.hash(r.getBaselineSeed().path("sources"));
        var input=JsonNodeFactory.instance.objectNode().put("contractVersion",1).put("reportId",r.getId().toString()).put("reportRevision",r.getRevision())
                .put("baselineRevision",r.getBaselineRevision()).put("targetRevision",r.getSourceContentRevision()).put("baselineHash",r.getBaselineHash())
                .put("targetHash",r.getSourceContentHash()).put("manifestHash",manifest);
        var configuration=JsonNodeFactory.instance.objectNode().put("leaseMillis",properties.leaseDuration().toMillis()).put("maxLeaseRecoveries",properties.maxLeaseRecoveries());
        var budgets=configuration.putObject("budgets");budgets.set("setting",mapper.toTree(properties.setting()));budgets.set("typo",mapper.toTree(properties.typo()));budgets.set("roles",mapper.toTree(properties.roles()));
        configuration.put("budgetRevision",1).putArray("budgetHistory");
        var models=configuration.putObject("models");for(var role:properties.roles().keySet()) {
            var model=models.putObject(role.name()).put("model",role==ValidationRole.QUERY_EMBEDDING?properties.queryEmbeddingModel():"gpt-6.1-terra");
            if(role.reasoningEffort()!=null)model.put("reasoningEffort",role.reasoningEffort());
            else model.put("version",retrieval.retrievalEmbeddingVersion()).put("dimensions",1536);
        }
        var job=jobs.save(ValidationJob.queue(r,journal.hash(input),manifest,configuration));r.attachValidationJob(job);
    }
    @Override @Transactional public Map<String,Object> claimValidationJob(Claim c) {
        if(!properties.enabled())throw error(VALIDATION_RUNTIME_DISABLED);
        if(c.contractVersion()!=1||!c.capabilities().contains("VALIDATION_EXECUTION_V1"))throw error(VALIDATION_RESULT_INVALID);
        for(UUID id:jobs.claimCandidates()) {
            var job=access.lock(id);
            if(job.getStatus()==ValidationExecutionStatus.RUNNING) {
                if(job.getLeaseExpiresAt()==null||java.time.Instant.now().isBefore(job.getLeaseExpiresAt()))continue;
                usage.releaseReservedValidationUsage(job,AiTokenUsageOutcome.WORKER_LEASE_EXPIRED);job.expire(job.getConfiguration().path("maxLeaseRecoveries").asInt());job.getReport().advanceVersion();
            }
            if(job.getStatus()!=ValidationExecutionStatus.QUEUED)continue;
            try { access.current(job.getReport()); } catch(org.monitoring.catchholebackend.global.exception.AppException exception) {job.cancel();continue;}
            job.claim(c.workerId(),lease(job));job.getReport().advanceVersion();em.flush();var view=mapper.toClaimResponse(job,snapshots.readCheckpoint(job));view.put("usage",usage.getValidationUsage(job.getId()));return view;
        }
        return null;
    }
    @Override @Transactional public Map<String,Object> heartbeatValidationJob(UUID id,UUID lease,Lease c) {
        var j=access.running(id,lease,c.scope());j.heartbeat(lease(j));return Map.of("scope",mapper.toScope(j),"leaseExpiresAt",j.getLeaseExpiresAt().toString());
    }
    @Override @Transactional public Map<String,Object> loadValidationCheckpoint(UUID id,UUID token) {
        var j=access.lock(id);access.require(j,token,mapper.toScope(j));var view=snapshots.readCheckpoint(j);view.put("usage",usage.getValidationUsage(id));view.put("budgetRevision",j.getConfiguration().path("budgetRevision").asInt(1));view.put("budgetHistory",mapper.toTransportValue(j.getConfiguration().path("budgetHistory")));return view;
    }
    @Override @Transactional public ValidationStagingResponse retryValidation(Long member,UUID work,UUID report,Retry c) {
        var owner=works.getOwnedWorkForUpdate(work,member);
        var input=mapper.toTree(Map.of("reportId",report,"expectedReportVersion",c.expectedReportVersion()));String hash=journal.hash(input);
        var existing=keys.findByWorkIdAndRequestKey(work,c.idempotencyKey());
        if(existing.isPresent()) {
            var k=existing.get();if(!"RETRY".equals(k.getRequestKind())||!hash.equals(k.getRequestHash()))throw error(VALIDATION_IDEMPOTENCY_CONFLICT);
            return k.getResponseSnapshot()==null?ValidationStagingResponse.from(k.getReport(),reviewGate.errorReviewStatus(k.getReport())):mapper.toStagingResponse(k.getResponseSnapshot());
        }
        if(!properties.enabled())throw error(VALIDATION_RUNTIME_DISABLED);
        var r=reports.findByIdAndWorkId(report,work).orElseThrow(()->error(VALIDATION_NOT_FOUND));em.refresh(r);access.currentInput(r,false);
        if(r.getVersion()!=c.expectedReportVersion())throw error(VALIDATION_REPORT_VERSION_CONFLICT);
        if(!episodes.findFirstByWorkIdAndStatusNotOrderByEpisodeNoDesc(work,org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.ARCHIVED).map(e->e.getId().equals(r.getEpisode().getId())).orElse(false))throw error(VALIDATION_TARGET_INVALID);
        data.validateSavedValidationSeed(member,work,report);
        if(r.getBaselineStatus()!=ValidationPreparationStatus.READY) {baselines.materialize(report);em.flush();em.refresh(r);}
        access.current(r);
        var j=jobs.findByReportId(report).orElse(null);
        if(j==null)queuePrepared(r);
        else {
            j=jobs.findForUpdate(j.getId()).orElseThrow();em.refresh(j);
            if(j.getStatus()!=ValidationExecutionStatus.PARTIAL&&j.getStatus()!=ValidationExecutionStatus.FAILED)throw error(VALIDATION_PUBLICATION_CONFLICT);
            budgetPolicy.refreshForRetry(j);usage.ensureValidationRetryCapacity(j);usage.releaseReservedValidationUsage(j,AiTokenUsageOutcome.USAGE_UNAVAILABLE);j.retry();r.advanceVersion();
        }
        var response=ValidationStagingResponse.from(r,reviewGate.errorReviewStatus(r));var key=ValidationRequestKey.create(owner,c.idempotencyKey(),r,"RETRY",hash);key.recordRetryResponse(mapper.toTree(response));keys.save(key);em.flush();return response;
    }
    private Duration lease(ValidationJob j) {return Duration.ofMillis(j.getConfiguration().path("leaseMillis").asLong());}
}
