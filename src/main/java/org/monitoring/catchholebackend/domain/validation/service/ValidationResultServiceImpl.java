package org.monitoring.catchholebackend.domain.validation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import jakarta.persistence.EntityManager;
import java.util.*;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;
import org.monitoring.catchholebackend.domain.validation.dto.ValidationExecutionContract.*;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationExecutionMapper;
import org.monitoring.catchholebackend.domain.validation.processor.*;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationPublicationValidator.*;
import org.monitoring.catchholebackend.domain.validation.repository.*;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.monitoring.catchholebackend.domain.validation.processor.ValidationExecutionAccess.error;
import static org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode.*;

/** External reads sit between two brief transactions; final source/lease checks occur under Work. */
@Service @RequiredArgsConstructor
public class ValidationResultServiceImpl implements ValidationResultService {
    private final PlatformTransactionManager transactions;
    private final ValidationExecutionAccess access;
    private final ValidationExecutionRepository results;
    private final ValidationJobRepository jobs;
    private final ValidationReportRepository reports;
    private final WorkRepository works;
    private final ValidationExecutionMapper mapper;
    private final ValidationPublicationValidator validator;
    private final ValidationDecisionValidator decisions;
    private final org.monitoring.catchholebackend.domain.validation.mapper.ValidationDataMapper dataMapper;
    private final ValidationUsageService usage;
    private final AnalysisStateJournal journal;
    private final ValidationJudgmentRepository judgments;
    private final EntityManager em;

    @Override public Map<String,Object> publishValidationPlan(UUID id,UUID lease,Publish c) {
        if(c.branch()!=ValidationBranch.SETTING||c.stage()==ValidationStageKind.TYPO_SEGMENT)throw error(VALIDATION_RESULT_INVALID);
        return publish(id,lease,c.scope(),"PLAN",c.idempotencyKey(),c,c.branch(),c.payload(),c.citations(),(j,p)-> {
            var all=results.stages(c.scope().reportId());var body=(ObjectNode)mapper.toTree(c.payload());
            if(c.failureCode()!=null) {if(body.hasNonNull("failure_code")&&!c.failureCode().equals(body.path("failure_code").asText()))throw error(VALIDATION_RESULT_INVALID);body.put("failure_code",c.failureCode());}
            String hash=journal.hash(body);
            if(c.failureCode()==null)validator.stage(p,c.stage(),c.ref(),mapper.toTree(c.payload()),all);
            else if(!ValidationPublicationValidator.contains(p.grid(),c.stage(),c.ref())||!safeCode(c.failureCode()))throw error(VALIDATION_RESULT_INVALID);
            var previous=all.stream().filter(s->s.getBranch()==c.branch()&&s.getStage()==c.stage()&&s.getRef().equals(c.ref())).findFirst();
            if(previous.isPresent()&&previous.get().isSuccessful()) {
                if(c.failureCode()!=null||!hash.equals(previous.get().getPayloadHash()))throw error(VALIDATION_PUBLICATION_CONFLICT);
                return new PublicationResult(stageReceipt(previous.get(),j),false);
            }
            if(c.stage()==ValidationStageKind.MERGED_PLAN&&c.failureCode()==null)merge(j,mapper.toTree(c.payload()),all,p);
            ValidationStage stage;
            if(previous.isPresent()) {stage=previous.get();stage.publish(hash,body,c.failureCode()==null);}
            else {stage=ValidationStage.create(j.getReport(),c.branch(),c.stage(),c.ref(),hash,body,c.failureCode()==null);results.save(stage);}
            saveEvidence(j,c.stage()+":"+c.ref(),c.citations());em.flush();return new PublicationResult(stageReceipt(stage,j),true);
        });
    }
    @Override public Map<String,Object> publishValidationUnit(UUID id,UUID lease,Unit c) {
        return publish(id,lease,c.scope(),"UNIT",c.idempotencyKey(),c,c.branch(),c.payload(),c.citations(),(j,p)-> {
            var body=mapper.toTree(c.payload());
            if(c.branch()==ValidationBranch.TYPO) {
                if(c.checkId()!=null||c.expectedCheckRevision()!=null||c.checkInputDigest()!=null||!Set.of("COMPLETED","INCOMPLETE").contains(c.outcome())
                        ||!ValidationPublicationValidator.contains(p.grid(),ValidationStageKind.TYPO_SEGMENT,c.ref()))throw error(VALIDATION_RESULT_INVALID);
                var previous=results.stages(j.getReport().getId()).stream().filter(s->s.getBranch()==ValidationBranch.TYPO&&s.getRef().equals(c.ref())).findFirst();
                if(previous.isPresent()&&previous.get().isSuccessful()) {
                    if(!"COMPLETED".equals(c.outcome())||!journal.hash(body).equals(previous.get().getPayloadHash()))throw error(VALIDATION_PUBLICATION_CONFLICT);
                    return new PublicationResult(stageReceipt(previous.get(),j),false);
                }
                if("COMPLETED".equals(c.outcome()))validateTypo(p,c.ref(),body);
                else if(!safeCode(body.path("failure_code").asText()))throw error(VALIDATION_RESULT_INVALID);
                if(previous.isPresent())previous.get().publish(journal.hash(body),body,"COMPLETED".equals(c.outcome()));
                else results.save(ValidationStage.create(j.getReport(),ValidationBranch.TYPO,ValidationStageKind.TYPO_SEGMENT,c.ref(),journal.hash(body),body,"COMPLETED".equals(c.outcome())));
                if("COMPLETED".equals(c.outcome()))for(var suggestion:body.path("suggestions"))results.save(ValidationFinding.create(j.getReport(),ValidationBranch.TYPO,c.ref(),null,null,suggestion.path("category").asText(),suggestion));
                saveEvidence(j,"TYPO:"+c.ref(),c.citations());em.flush();return new PublicationResult(Map.of("unitRef",c.ref(),"outcome",c.outcome()),true);
            }
            var check=check(j,c.checkId());
            if(c.expectedCheckRevision()==null||check.getRevision()!=c.expectedCheckRevision()||!check.getInputDigest().equals(c.checkInputDigest())||!check.getLocalRef().equals(c.ref()))throw error(VALIDATION_PUBLICATION_CONFLICT);
            if(!Set.of("CONFLICT","SUSPECTED_CONFLICT","NEEDS_CONTEXT","NO_CONFLICT","INCOMPLETE").contains(c.outcome()))throw error(VALIDATION_RESULT_INVALID);
            if(check.completed()) {
                if(!check.getOutcome().equals(c.outcome())||!journal.hash(check.getDecision()).equals(journal.hash(body)))throw error(VALIDATION_PUBLICATION_CONFLICT);
                return new PublicationResult(mapper.toCheckResponse(check),false);
            }
            if(!"INCOMPLETE".equals(c.outcome()))decisions.validate(p,check,c.outcome(),body);
            else if(!safeCode(body.path("failure_code").asText()))throw error(VALIDATION_RESULT_INVALID);
            check.decide(c.outcome(),body);
            if(!Set.of("NO_CONFLICT","INCOMPLETE").contains(c.outcome()))results.save(ValidationFinding.create(j.getReport(),ValidationBranch.SETTING,c.ref(),check.getId(),check.getRevision(),c.outcome(),body));
            saveEvidence(j,"CHECK:"+check.getId()+":"+check.getRevision(),c.citations());return new PublicationResult(mapper.toCheckResponse(check),true);
        });
    }
    @Override public Map<String,Object> reviseValidationChecks(UUID id,UUID lease,Revise c) {
        return publish(id,lease,c.scope(),"REVISE",c.idempotencyKey(),c,ValidationBranch.SETTING,Map.of("changes",c.changes()),c.citations(),(j,p)-> {
            Set<UUID> unique=new HashSet<>();var affected=new ArrayList<ValidationCheck>();
            for(var change:c.changes()) {
                var check=check(j,change.checkId());
                if(!unique.add(check.getId())||check.getRevision()!=change.expectedRevision()||!check.getInputDigest().equals(change.expectedInputDigest())||journal.hash(mapper.toTree(change.state())).equals(check.getInputDigest()))throw error(VALIDATION_PUBLICATION_CONFLICT);
                var state=decisions.state(mapper.toTree(change.state()),decisions.subjects(p));
                var oldIntents=check.getState().path("lookup_obligations");var newIntents=state.path("lookup_obligations");
                for(var intent:oldIntents)if(!containsIntent(newIntents,intent))throw error(VALIDATION_RESULT_INVALID);
                if(newIntents.size()<=oldIntents.size())throw error(VALIDATION_RESULT_INVALID);
                affected.add(check);
            }
            // Verify every CAS first; no old completion survives a partially applied follow-up.
            // The old judgment stays on its stale finding as history; the new revision starts without a judgment row (PENDING).
            for(int i=0;i<affected.size();i++) {var check=affected.get(i);results.save(ValidationCheckHistory.supersede(check,c.reason()));
                for(var judgment:judgments.findAllByCheckIdAndCheckRevision(check.getId(),check.getRevision()))results.save(ValidationJudgmentHistory.supersede(judgment));var state=decisions.state(mapper.toTree(c.changes().get(i).state()),decisions.subjects(p));check.revise(state,journal.hash(state),c.reason());}
            results.checkpoint(j.getReport().getId(),ValidationBranch.SETTING).ifPresent(ValidationCheckpoint::advanceForQuestionRevision);
            saveEvidence(j,"REVISION",c.citations());return new PublicationResult(Map.of("checks",affected.stream().map(mapper::toCheckResponse).toList()),true);
        });
    }
    @Override public Map<String,Object> publishValidationCheckpoint(UUID id,UUID lease,Checkpoint c) {
        return publish(id,lease,c.scope(),"CHECKPOINT",c.idempotencyKey(),c,c.branch(),c.payload(),c.citations(),(j,p)-> {
            var previous=results.checkpoint(j.getReport().getId(),c.branch());
            if(c.expectedVersion()!=(previous.isPresent()?previous.get().getVersion():0))throw error(VALIDATION_PUBLICATION_CONFLICT);
            var checkpoint=previous.orElseGet(()->ValidationCheckpoint.create(j.getReport(),c.branch()));checkpoint.publish(mapper.toTree(c.payload()));if(previous.isEmpty())results.save(checkpoint);
            saveEvidence(j,"CHECKPOINT:"+c.branch(),c.citations());return new PublicationResult(Map.of("branch",c.branch(),"version",checkpoint.getVersion()),true);
        });
    }
    @Override public Map<String,Object> completeValidationJob(UUID id,UUID lease,Complete c) {
        return transaction(junk->{
            var j=access.lock(id);var existing=receipt(j,"COMPLETE",c.idempotencyKey(),c);
            // A finish replay is allowed only for this still-current, unexpired lease/generation.
            if(!Objects.equals(j.getLeaseToken(),lease)||j.getLeaseExpiresAt()==null||!java.time.Instant.now().isBefore(j.getLeaseExpiresAt())||!mapper.toScope(j).equals(c.scope()))throw error(VALIDATION_LEASE_CONFLICT);
            access.current(j.getReport());if(existing!=null)return existing;
            access.require(j,lease,c.scope());validator.recheck(context(j,lease));
            boolean setting=complete(j,ValidationBranch.SETTING),typo=complete(j,ValidationBranch.TYPO);
            var status=setting&&typo?ValidationExecutionStatus.COMPLETED:successCount(j)>0?ValidationExecutionStatus.PARTIAL:ValidationExecutionStatus.FAILED;
            if(c.failureCode()!=null&&!safeCode(c.failureCode()))throw error(VALIDATION_RESULT_INVALID);
            j.finish(status,status==ValidationExecutionStatus.COMPLETED?null:c.failureCode()==null?"REQUIRED_PROCESSING_INCOMPLETE":c.failureCode());j.getReport().advanceVersion();usage.releaseReservedValidationUsage(j,AiTokenUsageOutcome.USAGE_UNAVAILABLE);
            var response=progressView(j);saveReceipt(j,"COMPLETE",c.idempotencyKey(),c,response);return response;
        });
    }
    @Override public Map<String,Object> getValidationProgress(Long member,UUID work,UUID report) {
        return transaction(junk->{works.getOwnedWork(work,member);var r=reports.findByIdAndWorkId(report,work).orElseThrow(()->error(VALIDATION_NOT_FOUND));
            var j=jobs.findByReportId(report).orElse(null);
            if(j==null)return Map.of("reportId",report,"reportVersion",r.getVersion(),"status",ValidationExecutionStatus.NOT_STARTED,"stages",List.of(),"checks",List.of(),"findings",List.of());
            return progressView(j);});
    }
    private Map<String,Object> publish(UUID id,UUID lease,Scope scope,String operation,String key,Object command,ValidationBranch branch,Object payload,List<Citation> citations,Publication action) {
        if(mapper.toTree(command).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>ValidationPublicationValidator.MAX_PAYLOAD_BYTES)throw error(VALIDATION_RESULT_INVALID);
        var replay=transaction(junk->{var j=access.running(id,lease,scope);requireCurrentUnit(j,command);return receipt(j,operation,key,command);});if(replay!=null)return replay;
        var snapshot=transaction(junk->{var j=access.running(id,lease,scope);return new Preparation(context(j,lease),j.getRequiredGrid()==null?null:j.getRequiredGrid().path(branch.name()).deepCopy());});
        String book=command instanceof Publish c&&c.stage()==ValidationStageKind.BOOK_SOURCE&&c.failureCode()==null?c.ref():null;
        Context context=snapshot.context();var prepared=validator.prepare(context,branch,snapshot.grid(),book);validator.payload(prepared,mapper.toTree(payload),citations);
        return transaction(junk->{var j=access.running(id,lease,scope);requireCurrentUnit(j,command);var existing=receipt(j,operation,key,command);if(existing!=null)return existing;validator.recheck(context);
            var old=j.getRequiredGrid()==null?null:j.getRequiredGrid().get(branch.name());var extended=validator.extendGrid(prepared,old);
            j.initializeGrid(branch,extended.grid());var publication=action.publish(j,extended);var response=mapper.toTransport(publication.response());if(publication.changed())j.getReport().advanceVersion();saveReceipt(j,operation,key,command,response);em.flush();return response;});
    }
    private void merge(ValidationJob j,JsonNode payload,List<ValidationStage> stages,Prepared p) {
        var grid=p.grid();
        if(!ValidationPublicationValidator.sourcesReady(grid))throw error(VALIDATION_RESULT_INVALID);
        for(var unit:grid.path("units"))if(!"MERGED_PLAN".equals(unit.path("stage").asText())&&stages.stream().noneMatch(s->s.isSuccessful()&&s.getBranch()==ValidationBranch.SETTING&&s.getStage().name().equals(unit.path("stage").asText())&&s.getRef().equals(unit.path("ref").asText())))throw error(VALIDATION_RESULT_INVALID);
        if(!payload.path("questions").isArray())throw error(VALIDATION_RESULT_INVALID);
        var originals=new HashMap<String,JsonNode>();for(var s:stages)if(s.getBranch()==ValidationBranch.SETTING&&Set.of(ValidationStageKind.PLANNER,ValidationStageKind.COVERAGE,ValidationStageKind.BOOK_COVERAGE).contains(s.getStage()))
            for(var q:s.getPayload().path("questions"))originals.put(s.getStage()+":"+s.getRef()+":"+q.path("local_ref").asText(),q);
        Set<String> covered=new HashSet<>(),locals=new HashSet<>();
        for(var q:payload.path("questions")) {
            String local=q.path("localRef").asText();if(local.isBlank()||local.length()>100||!locals.add(local)||!q.path("originalRefs").isArray()||q.path("originalRefs").isEmpty()||!q.path("state").isObject())throw error(VALIDATION_RESULT_INVALID);
            var spans=q.path("state").path("draft").path("target_spans");if(!spans.isArray()||spans.isEmpty())throw error(VALIDATION_RESULT_INVALID);
            Set<String> spanHashes=new HashSet<>();for(var span:spans)spanHashes.add(journal.hash(span));
            for(var ref:q.path("originalRefs")) {
                if(!ref.isTextual()||!originals.containsKey(ref.asText())||!covered.add(ref.asText()))throw error(VALIDATION_RESULT_INVALID);
                var originalDraft=originals.get(ref.asText());var mergedDraft=q.path("state").path("draft");
                for(String field:List.of("property_or_rule","time_context","assertion_mode"))if(!decisions.same(originalDraft.path(field),mergedDraft.path(field)))throw error(VALIDATION_RESULT_INVALID);
                for(String field:List.of("subject_refs","conditions"))if(!sameTextSet(originalDraft.path(field),mergedDraft.path(field)))throw error(VALIDATION_RESULT_INVALID);
                if(mergedDraft.path("subject_refs").isEmpty()&&q.path("originalRefs").size()!=1)throw error(VALIDATION_RESULT_INVALID);
                for(var intent:originalDraft.path("lookup_intents"))if(!containsIntent(mergedDraft.path("lookup_intents"),intent))throw error(VALIDATION_RESULT_INVALID);
                for(var original:originalDraft.path("target_spans"))if(!spanHashes.contains(journal.hash(original)))throw error(VALIDATION_RESULT_INVALID);
            }
            var state=decisions.state(q.path("state"),decisions.subjects(p));
            results.save(ValidationCheck.create(j.getReport(),local,q.path("originalRefs"),state,journal.hash(state)));
        }
        if(!covered.equals(originals.keySet()))throw error(VALIDATION_RESULT_INVALID);em.flush();
    }
    private void validateTypo(Prepared p,String ref,JsonNode body) {
        ValidationPublicationValidator.batchSegments(body,ref);if(!body.path("suggestions").isArray()||!body.path("failure_code").isNull()&&!body.path("failure_code").isMissingNode())throw error(VALIDATION_RESULT_INVALID);
        var bounds=ValidationPublicationValidator.segment(p.grid(),ref);var accepted=new ArrayList<JsonNode>();
        for(var stage:transaction(junk->results.stages(p.context().scope().reportId())))if(stage.getBranch()==ValidationBranch.TYPO&&stage.isSuccessful()&&!stage.getRef().equals(ref))for(var s:stage.getPayload().path("suggestions"))accepted.add(s.path("span"));
        for(var suggestion:body.path("suggestions")) {
            var span=suggestion.path("span");if(!Set.of("SPELLING","SPACING","TYPO").contains(suggestion.path("category").asText())||suggestion.path("reason").asText().isBlank()
                    ||!suggestion.path("replacement").isTextual()||suggestion.path("replacement").asText().equals(span.path("quote").asText())||!"T1".equals(span.path("source_ref").asText())
                    ||span.path("start").asInt()<bounds.path("start").asInt()||span.path("start").asInt()>=bounds.path("end").asInt()||span.path("end").asInt()>bounds.path("contextEnd").asInt())throw error(VALIDATION_RESULT_INVALID);
            for(var other:accepted)if(other.path("start").asInt()<span.path("end").asInt()&&other.path("end").asInt()>span.path("start").asInt())throw error(VALIDATION_RESULT_INVALID);accepted.add(span);
        }
        if("PLANNED".equals(body.path("segments").path(0).path("status").asText())!=!body.path("suggestions").isEmpty())throw error(VALIDATION_RESULT_INVALID);
    }
    private Map<String,Object> progressView(ValidationJob j) {
        var m=new LinkedHashMap<String,Object>();m.put("reportId",j.getReport().getId());m.put("reportVersion",j.getReport().getVersion());m.put("jobId",j.getId());m.put("status",j.getStatus());m.put("failureCode",j.getFailureCode());
        m.put("settingStatus",branchStatus(j,ValidationBranch.SETTING));m.put("typoStatus",branchStatus(j,ValidationBranch.TYPO));
        var checks=results.checks(j.getReport().getId());m.put("completedNoCheckSegments",noCheckSegments(j));m.put("completedChecks",checks.stream().filter(ValidationCheck::completed).count());m.put("totalChecks",checks.size());
        m.put("checks",checks.stream().map(c->{var v=new LinkedHashMap<String,Object>();v.put("checkId",c.getId());v.put("revision",c.getRevision());v.put("outcome",c.getOutcome());v.put("reason",c.getDecision()==null?null:c.getDecision().path("reason").asText());v.put("changeReason",c.getChangeReason());return v;}).toList());
        var stages=results.stages(j.getReport().getId());long required=0;if(j.getRequiredGrid()!=null)for(var grid:j.getRequiredGrid())required+=grid.path("units").size();
        m.put("coverage",Map.of("gridReady",j.isGridReady(),"requiredStages",required,"completedStages",stages.stream().filter(ValidationStage::isSuccessful).count(),"stages",stages.stream().map(s->Map.of("branch",s.getBranch(),"stage",s.getStage(),"ref",s.getRef(),"successful",s.isSuccessful())).toList()));
        m.put("findings",results.findings(j.getReport().getId()).stream().filter(f->f.getCheckId()==null||checks.stream().anyMatch(c->c.getId().equals(f.getCheckId())&&Objects.equals(c.getRevision(),f.getCheckRevision()))).map(this::publicFinding).toList());
        Set<String> publicOwners=new HashSet<>();for(var check:checks)if(check.completed())publicOwners.add("CHECK:"+check.getId()+":"+check.getRevision());for(var stage:stages)if(stage.getBranch()==ValidationBranch.TYPO&&stage.isSuccessful())publicOwners.add("TYPO:"+stage.getRef());
        m.put("evidence",results.evidence(j.getReport().getId()).stream().filter(e->publicOwners.contains(e.getOwnerRef())).map(e->Map.of("sourceRef",e.getSourceRef(),"start",e.getStart(),"end",e.getEnd(),"quote",e.getQuote())).distinct().toList());
        m.put("extractionJobId",null);m.put("candidateGeneration",null);return m;
    }
    private Map<String,Object> publicFinding(ValidationFinding f) {
        var m=new LinkedHashMap<String,Object>();m.put("findingId",f.getId());m.put("branch",f.getBranch());m.put("checkId",f.getCheckId());m.put("checkRevision",f.getCheckRevision());m.put("outcome",f.getOutcome());
        var body=JsonNodeFactory.instance.objectNode();for(String field:List.of("reason","impact","impact_reason","target_spans","span","replacement","category"))if(f.getPayload().has(field))body.set(field,f.getPayload().path(field));m.put("payload",mapper.toTransportValue(body));return m;
    }

    private boolean complete(ValidationJob j,ValidationBranch branch) {
        if(j.getRequiredGrid()==null||!j.getRequiredGrid().has(branch.name()))return false;
        if(branch==ValidationBranch.SETTING&&!ValidationPublicationValidator.sourcesReady(j.getRequiredGrid().path(branch.name())))return false;
        var stages=results.stages(j.getReport().getId());for(var row:j.getRequiredGrid().path(branch.name()).path("units"))if(stages.stream().noneMatch(s->s.isSuccessful()&&s.getBranch()==branch&&s.getStage().name().equals(row.path("stage").asText())&&s.getRef().equals(row.path("ref").asText())))return false;
        return branch!=ValidationBranch.SETTING||results.checks(j.getReport().getId()).stream().allMatch(ValidationCheck::completed);
    }
    private String branchStatus(ValidationJob j,ValidationBranch branch) {if(j.getStatus()==ValidationExecutionStatus.CANCELED)return "CANCELED";if(complete(j,branch))return "COMPLETED";
        boolean success=branch==ValidationBranch.TYPO?results.stages(j.getReport().getId()).stream().anyMatch(s->s.getBranch()==branch&&s.isSuccessful()):noCheckSegments(j)>0||results.checks(j.getReport().getId()).stream().anyMatch(ValidationCheck::completed);return success?"PARTIAL":j.getStatus()==ValidationExecutionStatus.RUNNING?"RUNNING":j.getStatus()==ValidationExecutionStatus.QUEUED?"QUEUED":"FAILED";}
    private long successCount(ValidationJob j) {return noCheckSegments(j)+results.stages(j.getReport().getId()).stream().filter(s->s.isSuccessful()&&s.getBranch()==ValidationBranch.TYPO).count()+results.checks(j.getReport().getId()).stream().filter(ValidationCheck::completed).count();}
    private long noCheckSegments(ValidationJob j) {
        if(j.getRequiredGrid()==null||!j.getRequiredGrid().has("SETTING"))return 0;
        var grid=j.getRequiredGrid().path("SETTING");var stages=results.stages(j.getReport().getId());long count=0;
        if(!ValidationPublicationValidator.sourcesReady(grid))return 0;
        for(var segment:grid.path("segments")) {
            String ref=segment.path("ref").asText();boolean ready=true;
            for(var required:grid.path("units")) {
                String kind=required.path("stage").asText(),unit=required.path("ref").asText();
                if((unit.equals(ref)||unit.startsWith(ref+":"))||kind.equals("BOOK_SOURCE"))
                    if(stages.stream().noneMatch(s->s.getBranch()==ValidationBranch.SETTING&&s.isSuccessful()&&s.getStage().name().equals(kind)&&s.getRef().equals(unit)))ready=false;
            }
            if(!ready)continue;
            for(var stage:stages)if(stage.getBranch()==ValidationBranch.SETTING)for(var question:stage.getPayload().path("questions")) {
                JsonNode spans=stage.getStage()==ValidationStageKind.MERGED_PLAN?question.path("state").path("draft").path("target_spans"):question.path("target_spans");
                for(var span:spans)if(overlaps(span,segment))ready=false;
            }
            for(var check:results.checks(j.getReport().getId()))for(var span:check.getState().path("draft").path("target_spans"))if(overlaps(span,segment))ready=false;
            if(ready)count++;
        }return count;
    }
    private boolean overlaps(JsonNode span,JsonNode segment) {return span.path("start").asInt()<segment.path("end").asInt()&&span.path("end").asInt()>segment.path("start").asInt();}
    private ValidationCheck check(ValidationJob j,UUID id) {return results.checks(j.getReport().getId()).stream().filter(c->c.getId().equals(id)).findFirst().orElseThrow(()->error(VALIDATION_PUBLICATION_CONFLICT));}
    private Context context(ValidationJob j,UUID lease) {var r=j.getReport();return new Context(j.getId(),lease,mapper.toScope(j),r.getMember().getId(),r.getWork().getId(),r.getBaselineHash(),r.getSourceContentHash(),j.getConfiguration().deepCopy(),r.getBaselineEntries().deepCopy(),new LinkedHashMap<>(dataMapper.toSourceReferences(r)));}
    private boolean sameTextSet(JsonNode a,JsonNode b) {var x=new TreeSet<String>();var y=new TreeSet<String>();a.forEach(n->x.add(n.asText()));b.forEach(n->y.add(n.asText()));return x.equals(y);}
    private boolean containsIntent(JsonNode intents,JsonNode required) {for(var intent:intents)if(decisions.same(decisions.normalizeIntent(intent),decisions.normalizeIntent(required)))return true;return false;}
    private void requireCurrentUnit(ValidationJob job,Object command) {
        if(command instanceof Unit c && c.branch()==ValidationBranch.SETTING) {
            var check=check(job,c.checkId());if(c.expectedCheckRevision()==null||check.getRevision()!=c.expectedCheckRevision()||!check.getInputDigest().equals(c.checkInputDigest()))throw error(VALIDATION_PUBLICATION_CONFLICT);
        }
    }
    private Map<String,Object> stageReceipt(ValidationStage s,ValidationJob j) {return Map.of("stageId",s.getId(),"branch",s.getBranch(),"stage",s.getStage(),"ref",s.getRef(),"successful",s.isSuccessful(),"checks",results.checks(j.getReport().getId()).stream().map(mapper::toCheckResponse).toList());}
    private void saveEvidence(ValidationJob j,String owner,List<Citation> citations) {for(var c:citations)results.save(ValidationEvidence.create(j.getReport(),owner,c));}
    private String hash(String operation,Object command) {var node=(ObjectNode)mapper.toTree(command);if(!"COMPLETE".equals(operation))node.remove("scope");return journal.hash(JsonNodeFactory.instance.objectNode().put("operation",operation).set("command",node));}
    private Map<String,Object> receipt(ValidationJob j,String operation,String key,Object command) {var found=results.publication(j.getReport().getId(),key);if(found.isEmpty())return null;if(!hash(operation,command).equals(found.get().getRequestHash()))throw error(VALIDATION_IDEMPOTENCY_CONFLICT);return mapper.toTransport(found.get().getResponse());}
    private void saveReceipt(ValidationJob j,String operation,String key,Object command,Map<String,Object> response) {results.save(ValidationPublication.create(j.getReport(),key,hash(operation,command),mapper.toTree(response)));}
    private boolean safeCode(String code) {return code!=null&&code.matches("[A-Z][A-Z0-9_]{0,99}");}
    private <T>T transaction(Function<Void,T> action) {return new TransactionTemplate(transactions).execute(s->action.apply(null));}
    private record PublicationResult(Map<String,Object> response,boolean changed) {}
    private record Preparation(Context context,JsonNode grid) {}
    private interface Publication {PublicationResult publish(ValidationJob j,Prepared p);}
}
