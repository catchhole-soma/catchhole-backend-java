package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.monitoring.catchholebackend.domain.upload.entity.*;
import org.monitoring.catchholebackend.domain.upload.type.*;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationSettingsApplicationRequest;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationSettingsApplicationResponse;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.domain.validation.service.ValidationSettingsApplicationService;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.worldsetting.entity.*;
import org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingCandidateService;
import org.monitoring.catchholebackend.domain.worldsetting.type.*;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.storage.StoredTextObject;
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
    @Autowired List<AnalysisJournalContributor> journalContributors;
    @Autowired org.monitoring.catchholebackend.domain.validation.mapper.ValidationReviewMapper reviewMapper;

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

    /**
     * 선택적 자동 반영 도입 전에 저장된 SUCCEEDED/SEALED + HELD_REVIEW 상태를 합성한다.
     * 현행 자동 경로 테스트는 completeExtraction을 사용한다. 이 fixture는 기존 보류 데이터의 명시 반영·복구 호환성만 검증한다.
     */
    void completeLegacyHeldExtraction(Release r) {
        tx.executeWithoutResult(s->{
            var job=analysisJobs.findById(r.job()).orElseThrow();
            job.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED);
            journalContributors.forEach(contributor->contributor.finalizeChanges(job));states.seal(job);
            var report=em.find(org.monitoring.catchholebackend.domain.validation.entity.ValidationReport.class,r.f().report());
            var decision=applications.evaluate(report);
            assertThat(decision.kind()).as("legacy fixture requires a held plan").isEqualTo(org.monitoring.catchholebackend.domain.validation.processor.ValidationApplicationGate.Kind.HELD);
            var receipt=org.monitoring.catchholebackend.domain.validation.entity.ValidationSettingsApplication.start(report,decision.plan().completion());
            report.advanceVersion();
            receipt.hold(org.monitoring.catchholebackend.domain.validation.type.ValidationReviewTrigger.AUTOMATIC,null,null,reviewMapper.toHoldReasonTree(decision.reasons()),null,report.getVersion());
            em.persist(receipt);job.holdReviewReleaseApplication();job.succeed("{}",0,0);job.getEpisode().markAnalyzed();
        });
    }

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

    // ---- D2(#387): 같은 작품의 순차 분석이 끝나지 않았으면 회차 전체 확정을 쓰지 않는다 (H2·PostgreSQL 공통 시나리오) ----
    /** 같은 작품에 설정집을 올려 자동 누적 추출 Job 하나를 큐에 넣는다(PENDING). 업로드 서비스와 같은 생성·실행 초기화이며 claim하지 않는다. */
    UUID queueSettingBookAnalysis(Fixture f) {
        return tx.execute(s->{
            var work=em.find(Work.class,f.work());
            var batch=UploadBatch.create(work,work.getMember(),UploadType.SETTING_BOOK,UploadSourceType.FILE);em.persist(batch);
            var book=UploadFile.create(batch,UploadFileRole.SETTING_BOOK,"합성 설정집.txt","text/plain","synthetic/book",4);em.persist(book);
            var revision=SettingBookRevision.create(book,1,new StoredTextObject("synthetic/revision/"+UUID.randomUUID(),"v1","b".repeat(64),4));em.persist(revision);book.linkCurrentRevision(revision);
            var job=AnalysisJob.createSettingBook(work,book,revision);
            job.initializeOrderedRun(UUID.randomUUID(),1,0,null,JSON.objectNode().put("automaticInputCapturedAtClaim",true));em.persist(job);
            return job.getId();
        });
    }
    /** 설정집 분석이 끝난다. 끝난 실행(취소·무효)은 미완료 순차 분석이 아니다. */
    void finishSettingBookAnalysis(UUID job) {tx.executeWithoutResult(s->analysisJobs.findById(job).orElseThrow().invalidateJournal("합성 설정집 분석 종료"));}
    List<ValidationReviewAction> allowedActions(Fixture f) {return reviews.getValidationReview(f.member(),f.work(),f.report()).allowedActions();}

    /**
     * 보류된 release Job(SUCCEEDED·SEALED)은 미완료가 아니다. 설정집 자동 누적 Job이 대기·실행 중이면 READY 회차 전체 확정은 409
     * ANALYSIS_REVIEW_WAIT_REQUIRED이고 receipt·report version·확정 설정·release Job 보류 표지가 그대로이며 APPLY_SETTINGS를 안내하지 않는다.
     * 보류(HELD) 기록은 설정을 쓰지 않아 그동안에도 지금처럼 접수된다. 분석이 끝나면 안내와 반영이 함께 돌아온다.
     */
    void applyWaitsForUnfinishedOrderedAnalysis() {
        var r=release();var f=r.f();
        var held=inJob(r.job(),job->{var d=discovery(job,"에르웬");stat(job,d.getProvisionalSubjectKey(),"에르웬",35,CharacterFactOperation.ADD);world(job,"엘프","서식지","북부");
            var d2=discovery(job,"비요른");return stat(job,d2.getProvisionalSubjectKey(),"비요른",50,CharacterFactOperation.REVIEW_REQUIRED).getId();});
        completeExtraction(r);
        assertThat(applicationRow(f).get("status")).isEqualTo("HELD_REVIEW");assertThat(jobState(r.job())).isEqualTo("SUCCEEDED/SEALED");assertThat(heldAt(r.job())).isNotNull();
        assertThat(analysisJobs.existsUnfinishedOrderedAnalysis(f.work())).as("held release job is finished").isFalse();
        assertThat(allowedActions(f)).contains(ValidationReviewAction.APPLY_SETTINGS);

        var first=queueSettingBookAnalysis(f);
        assertThat(analysisJobs.existsUnfinishedOrderedAnalysis(f.work())).isTrue();
        assertThat(allowedActions(f)).doesNotContain(ValidationReviewAction.APPLY_SETTINGS);
        var stillHeld=apply(f,"held-while-book-runs");
        assertThat(stillHeld.status()).as("HELD writes no settings and is accepted as before").isEqualTo(ValidationApplicationStatus.HELD_REVIEW);assertThat(stillHeld.attempt()).isEqualTo(2);
        finishSettingBookAnalysis(first);
        characterReview.dismissSettingCandidate(f.member(),f.work(),held);

        var book=queueSettingBookAnalysis(f);
        for(String status:List.of("PENDING","RUNNING")) {
            jdbc.update("update analysis_jobs set status=? where id=?",status,book);
            long version=reportVersion(f);
            assertThat(allowedActions(f)).as(status).doesNotContain(ValidationReviewAction.APPLY_SETTINGS);
            assertThatThrownBy(()->apply(f,"wait-"+status)).isInstanceOf(AppException.class)
                    .extracting(e->((AppException)e).getResultCode().getCode()).isEqualTo("ANALYSIS_REVIEW_WAIT_REQUIRED");
            var row=applicationRow(f);
            assertThat(row.get("status")).isEqualTo("HELD_REVIEW");assertThat(((Number)row.get("attempt")).longValue()).isEqualTo(2);assertThat(reportVersion(f)).isEqualTo(version);
            assertThat(applicationRows(f)).isEqualTo(1);assertThat(heldAt(r.job())).isNotNull();assertThat(automaticAppliedAt(r.job())).isNull();
            assertThat(confirmed(f)).isZero();assertThat(characters(f)).isZero();assertThat(facts(f)).isZero();assertThat(worldSettings(f)).isZero();
        }
        finishSettingBookAnalysis(book);
        assertThat(analysisJobs.existsUnfinishedOrderedAnalysis(f.work())).isFalse();
        assertThat(allowedActions(f)).contains(ValidationReviewAction.APPLY_SETTINGS);
        var applied=apply(f,"after-book-analysis");
        assertThat(applied.status()).isEqualTo(ValidationApplicationStatus.APPLIED);assertThat(applied.attempt()).isEqualTo(3);
        assertThat(characters(f)).isEqualTo(2);assertThat(facts(f)).isEqualTo(1);assertThat(worldSettings(f)).isEqualTo(1);assertThat(automaticAppliedAt(r.job())).isNotNull();
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
