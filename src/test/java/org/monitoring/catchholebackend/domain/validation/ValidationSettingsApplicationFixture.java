package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import org.monitoring.catchholebackend.domain.analysis.dto.request.*;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobPayload;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.service.*;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.character.entity.*;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.character.processor.*;
import org.monitoring.catchholebackend.domain.character.service.*;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationSettingsApplicationRequest;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationSettingsApplicationResponse;
import org.monitoring.catchholebackend.domain.validation.service.ValidationSettingsApplicationService;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.worldsetting.entity.*;
import org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingCandidateService;
import org.monitoring.catchholebackend.domain.worldsetting.type.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * GH312 S3: 정상 0건 검수 → 자동 검토 종료 → release Job을 실제 claim·완료 경계로 진행하는 fixture. 후보·비교 결과는
 * AutomaticAnalysisIntegrationTest와 같은 저장 경로(누적 상태·비교 batch·journal 변경)로 합성하며 모델 호출은 없다. 테스트 트랜잭션을 쓰지 않는다.
 */
abstract class ValidationSettingsApplicationFixture extends ValidationReviewCompletionServiceFixture {
    static final JsonNodeFactory JSON=JsonNodeFactory.instance;
    @Autowired AnalysisJobWorkerService analysisWorker;
    @Autowired AnalysisJobRepository analysisJobs;
    @Autowired AnalysisRunStateService states;
    @Autowired CharacterAnalysisStateService characterStates;
    @Autowired SettingCandidateService characterReview;
    @Autowired SettingCandidatePromotionService promotions;
    @Autowired WorldSettingCandidateService worldReview;
    @Autowired ValidationSettingsApplicationService applications;

    record Release(Fixture f,UUID job,WorkerAnalysisJobPayload payload) {}
    /** 합성 후보 근거가 가리키는 현재 원고. 파일 교체 테스트는 교체한 원고로 바꾼다. */
    String sourceText=TEXT;

    /** 검수 정상 0건 완료가 만든 release Job을 claim한다. 작품에는 합성 STAT schema를 둔다. */
    Release release() {
        var f=fixture();var run=runToCompletion(f,false);var job=(UUID)run.done().get("extractionJobId");assertThat(job).isNotNull();
        // schema는 검수 입력의 일부라 검사 뒤에 추가한다. 추출 claim은 이 schema를 읽는다.
        tx.executeWithoutResult(s->em.persist(CharacterSettingSchema.create(em.find(Work.class,f.work()),"stats.mental",null,"정신",CharacterFactType.STAT,SettingValueType.NUMBER,
                CharacterSettingValueSemantics.BASE_VALUE,CharacterSettingMergePolicy.REPLACE,JSON.arrayNode(),CharacterSettingSchemaSource.SYSTEM_SEED,true)));
        var payload=analysisWorker.claimAnalysisJob(claimRequest()).orElseThrow();assertThat(payload.analysisJobId()).isEqualTo(job);
        return new Release(f,job,payload);
    }
    static WorkerAnalysisJobClaimRequest claimRequest() {
        return new WorkerAnalysisJobClaimRequest("synthetic-no-llm","회차 전체 확정",Set.of(AnalysisJobType.SETTING_EXTRACTION),Set.of(AnalysisMode.ORDERED_PROVISIONAL),null,null,null,null,true);
    }
    <T> T inJob(UUID job,Function<AnalysisJob,T> body) {return tx.execute(s->body.apply(analysisJobs.findById(job).orElseThrow()));}
    /** 비교 완료 checkpoint를 커밋한 뒤 테스트 트랜잭션 없이 Worker 완료를 호출한다. */
    void completeExtraction(UUID job,UUID lease) {
        tx.executeWithoutResult(s->analysisJobs.findById(job).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED));
        analysisWorker.completeAnalysisJob(job,lease,new WorkerAnalysisJobCompleteRequest("{}",null,null));
    }
    void completeExtraction(Release r) {completeExtraction(r.job(),r.payload().leaseToken());}

    // ---- 합성 후보: 원고 TEXT("처음 합성 원고")의 실제 code point 위치를 근거로 쓴다 ----
    static ArrayNode evidence(String text,String quote) {
        int start=text.indexOf(quote);
        return JSON.arrayNode().add(JSON.objectNode().put("quote",quote).put("startOffset",text.codePointCount(0,start)).put("endOffset",text.codePointCount(0,start+quote.length())));
    }
    SettingCandidate discovery(AnalysisJob job,String name) {
        var candidate=SettingCandidate.createCharacterDiscovery(job.getWork(),job.getEpisode(),null,job,name,name,null,SettingCandidateMatchStatus.UNRESOLVED,evidence(sourceText,"합성"),BigDecimal.ONE,null);
        em.persist(candidate);ReflectionTestUtils.setField(candidate,"provisionalSubjectKey",CharacterAnalysisStateMapper.provisionalRef(candidate.getId()));em.flush();
        characterStates.prepareProvisionalCandidates(job);return candidate;
    }
    /** 임시 인물의 STAT 후보와 완료된 비교 결정. operation이 REVIEW_REQUIRED면 AI가 판단을 보류한 정상 비교 완료다. */
    SettingCandidate stat(AnalysisJob job,String provisional,String entityName,int value,CharacterFactOperation operation) {
        var candidate=SettingCandidate.create(job.getWork(),job.getEpisode(),null,job,SettingEntityType.CHARACTER,entityName,entityName,null,SettingCandidateMatchStatus.UNRESOLVED,
                "stats.mental",String.valueOf(value),SettingValueType.NUMBER,JSON.objectNode().put("value",value),evidence(sourceText,"원고"),BigDecimal.ONE,null);
        ReflectionTestUtils.setField(candidate,"provisionalSubjectKey",provisional);em.persist(candidate);em.flush();
        characterStates.prepareProvisionalCandidates(job);
        JsonNode target=characterStates.getTarget(job,null,provisional);
        var batch=CharacterFactComparisonBatch.createProvisional(job.getWork(),job.getEpisode(),job,provisional,CharacterFactType.STAT,1);
        ObjectNode context=JSON.objectNode();var slots=context.putArray("slots");target.path("slots").forEach(slot->slots.add(slot.deepCopy()));batch.recordAnalysisContext(context);em.persist(batch);
        candidate.startComparison(batch,"C1");candidate.recordComparisonContext(batch.getBaseSnapshotVersion(),job.getInputStateHash());
        candidate.completeComparison(operation,CharacterFactType.STAT,"stats.mental",String.valueOf(value),JSON.objectNode().put("value",value),JSON.arrayNode(),CharacterFactTemporalScope.PRESENT,
                "원문 수치",JSON.objectNode(),LocalDateTime.now(),"stats.mental",JSON.arrayNode());
        batch.complete("b".repeat(64),JSON.objectNode());
        characterStates.recordDecision(job,candidate,new CharacterSnapshotEntry(new CharacterSnapshotSlot(CharacterFactType.STAT,"stats.mental"),String.valueOf(value),JSON.objectNode().put("value",value),true),List.of(),List.of());
        return candidate;
    }
    /** 새 세계관 대상의 ADD 비교 결정과 그 journal 변경. */
    WorldSettingCandidate world(AnalysisJob job,String subject,String setting,String value) {
        var candidate=WorldSettingCandidate.create(job.getWork(),job.getEpisode(),job,WorldSettingCategory.RACE,subject,setting,value,JSON.arrayNode(),BigDecimal.ONE,null);em.persist(candidate);
        String key="provisional-world:"+candidate.getId();
        candidate.resolveOrderedSubject(WorldSettingSubjectResolutionType.NEW,key,subject,JSON.arrayNode(),JSON.arrayNode().add(key));
        var batch=WorldSettingComparisonBatch.createOrdered(job.getWork(),job.getEpisode(),job,WorldSettingCategory.RACE,null,WorldSettingSubjectResolutionType.NEW,key,subject,JSON.arrayNode(),JSON.arrayNode().add(key),1);em.persist(batch);
        candidate.startComparison(batch,"C1");
        var decision=WorldSettingComparisonDecision.create(batch,"D1",subject,null,null,null,WorldSettingConsolidationStatus.SINGLE,WorldSettingSuggestedOperation.ADD,null,null,setting,null,value,"원문 근거",null);
        decision.bindOrderedTarget(key,0L);em.persist(decision);candidate.completeComparison(decision,LocalDateTime.now());
        em.persist(WorldSettingComparisonDecisionSource.create(batch,decision,candidate,"C1",0));batch.complete("c".repeat(64),JSON.objectNode());
        ObjectNode target=JSON.objectNode();target.putNull("actualWorldSettingId");target.put("provisionalSubjectKey",key);target.put("category","RACE");target.put("subjectName",subject);
        target.put("normalizedSubjectName",subject);target.put("version",1);target.putObject("propertiesJson").put(setting,value);target.putObject("provenanceByPath");
        states.appendValidatedChanges(job,job.getInputStateHash(),List.of(new AnalysisStateChange("world-decision:"+decision.getId(),List.of("worldSettings",key),target,false,"ADD",List.of(candidate.getId()))));
        return candidate;
    }
    /** AI가 범위를 정하지 못한 세계관 후보(정상 비교 완료 + REVIEW_REQUIRED + SCOPE_UNRESOLVED). journal 변경은 ADD와 같은 경로로 덮는다. */
    WorldSettingCandidate scopeUnresolvedWorld(AnalysisJob job,String subject,String setting,String value) {
        var candidate=world(job,subject,setting,value);
        ReflectionTestUtils.setField(candidate,"suggestedOperation",WorldSettingSuggestedOperation.REVIEW_REQUIRED);
        ReflectionTestUtils.setField(candidate,"comparisonReviewReason",WorldSettingComparisonReviewReason.SCOPE_UNRESOLVED);
        ReflectionTestUtils.setField(candidate.getComparisonDecision(),"suggestedOperation",WorldSettingSuggestedOperation.REVIEW_REQUIRED);
        return candidate;
    }
    /** 정상 인물 발견 + STAT ADD + 세계관 ADD. 모두 기존 자동 반영 규칙이 받아들이는 후보다. */
    record Candidates(UUID discovery,UUID stat,UUID world) {}
    Candidates applicable(Release r) {
        return inJob(r.job(),job->{var d=discovery(job,"에르웬");var s=stat(job,d.getProvisionalSubjectKey(),"에르웬",35,CharacterFactOperation.ADD);var w=world(job,"엘프","서식지","북부");return new Candidates(d.getId(),s.getId(),w.getId());});
    }

    // ---- 조회 ----
    ValidationSettingsApplicationResponse apply(Fixture f,String key) {return applications.applySettings(f.member(),f.work(),f.report(),new ValidationSettingsApplicationRequest(reportVersion(f),key));}
    long characters(Fixture f) {return jdbc.queryForObject("select count(*) from characters where work_id=? and name<>'합성 인물'",Long.class,f.work());}
    long facts(Fixture f) {return jdbc.queryForObject("select count(*) from character_facts f join characters c on c.id=f.character_id where c.work_id=?",Long.class,f.work());}
    long snapshotSources(Fixture f) {return jdbc.queryForObject("select count(*) from character_snapshot_sources s join characters c on c.id=s.character_id where c.work_id=?",Long.class,f.work());}
    long worldSettings(Fixture f) {return jdbc.queryForObject("select count(*) from world_settings where work_id=?",Long.class,f.work());}
    long confirmed(Fixture f) {
        return jdbc.queryForObject("select count(*) from setting_candidates where work_id=? and review_status='CONFIRMED'",Long.class,f.work())
                +jdbc.queryForObject("select count(*) from world_setting_candidates where work_id=? and review_status='CONFIRMED'",Long.class,f.work());
    }
    long applicationRows(Fixture f) {return jdbc.queryForObject("select count(*) from validation_settings_applications where work_id=?",Long.class,f.work());}
    Map<String,Object> applicationRow(Fixture f) {return jdbc.queryForMap("select status,attempt,trigger,last_error_code,applied_at,extraction_job_generation,hold_reasons from validation_settings_applications where report_id=?",f.report());}
    java.sql.Timestamp automaticAppliedAt(UUID job) {return jdbc.queryForObject("select automatic_applied_at from analysis_jobs where id=?",java.sql.Timestamp.class,job);}
    String jobState(UUID job) {return jdbc.queryForObject("select status||'/'||journal_status from analysis_jobs where id=?",String.class,job);}
    Episode episode(Fixture f) {return tx.execute(s->em.find(Episode.class,f.target()));}
    String factValue(Fixture f,String character) {
        return jdbc.queryForObject("select f.fact_value from character_facts f join characters c on c.id=f.character_id where c.work_id=? and c.name=?",String.class,f.work(),character);
    }
    List<WorldSetting.Property> worldProperties(Fixture f,String subject) {
        return tx.execute(s->em.createQuery("select w from WorldSetting w where w.work.id=:w and w.subjectName=:n",WorldSetting.class).setParameter("w",f.work()).setParameter("n",subject).getSingleResult().getProperties());
    }
    String reportStatus(UUID report) {return jdbc.queryForObject("select status from validation_reports where id=?",String.class,report);}
    java.sql.Timestamp heldAt(UUID job) {return jdbc.queryForObject("select review_application_held_at from analysis_jobs where id=?",java.sql.Timestamp.class,job);}
    java.time.LocalDateTime updatedAt(UUID candidate) {return tx.execute(s->em.find(SettingCandidate.class,candidate).getUpdatedAt());}
}
