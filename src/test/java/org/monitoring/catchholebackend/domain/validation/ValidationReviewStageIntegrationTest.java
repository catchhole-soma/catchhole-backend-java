package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobFailRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisBatchSummaryResponse;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobService;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeSummaryResponse;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeAnalysisStatus;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationReviewCompletionRequest;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationReportHistoryResponse;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * GH312 S4 파생 검수 단계(reviewStage)를 단건·원고 목록·배치 요약·이력에서 같은 값으로 확인하고, 이전 report의 순차 실패 release Job이
 * 재검수 뒤 새 release를 막던 경로를 실제 claim·실패·완료 경계로 검증한다. S3 fixture처럼 테스트 트랜잭션 없이 메서드마다 새 Context를 쓴다.
 */
@SpringBootTest(properties="spring.config.import=") @ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@DisplayName("GH312 S4 검수 단계 projection과 실패 release Job 재검수")
class ValidationReviewStageIntegrationTest extends ValidationSettingsApplicationFixture {
    @Autowired AnalysisJobService analysisJobsApi;
    @BeforeEach void chunks() {jdbc.execute("create table if not exists episode_chunks (id uuid primary key, episode_id uuid)");}

    static String code(Throwable e) {return ((AppException)e).getResultCode().getCode();}
    EpisodeSummaryResponse episodeRow(Fixture f) {return episodes.getEpisodes(f.member(),f.work()).stream().filter(e->e.id().equals(f.target())).findFirst().orElseThrow();}
    AnalysisBatchSummaryResponse batchCard(Fixture f) {
        return analysisJobsApi.getAnalysisBatches(f.member(),f.work(),0,20).content().stream().filter(b->b.batchId().equals(reportBatch(f))).findFirst().orElseThrow();
    }
    ValidationReviewStage batchStage(Fixture f) {return batchCard(f).validationReports().stream().filter(v->v.reportId().equals(f.report())).findFirst().orElseThrow().reviewStage();}
    /** 네 projection이 모두 같은 단계를 낸다. */
    void stage(Fixture f,ValidationReviewStage expected) {
        assertThat(admissions.get(f.member(),f.work(),f.report()).reviewStage()).as("staging").isEqualTo(expected);
        assertThat(reviews.getValidationReview(f.member(),f.work(),f.report()).reviewStage()).as("review summary").isEqualTo(expected);
        assertThat(episodeRow(f).validationReviewStage()).as("episode list").isEqualTo(expected);
        assertThat(batchStage(f)).as("batch summary").isEqualTo(expected);
        assertThat(history(f).getFirst().reviewStage()).as("history").isEqualTo(expected);
    }
    List<ValidationReportHistoryResponse> history(Fixture f) {return reviews.getValidationHistory(f.member(),f.work(),f.target(),0,20).content();}
    /** 기준 변경 신호. 확정 설정 직접 수정은 GH201이 순차 실패 Job 동안 막으므로, 같은 신호를 내는 비교 callback 경로 대신 서비스 신호만 보낸다. */
    void confirmedSettingsChanged(Fixture f) {
        tx.executeWithoutResult(s->{em.find(Work.class,f.work(),LockModeType.PESSIMISTIC_WRITE);states.confirmedSettingsChanged(f.work());});
    }

    @Test @DisplayName("보류와 반영은 단건·원고 목록·배치 요약·이력에서 같은 단계이고 보류 release Job은 배치 요약에서 실패로 세지 않는다")
    void heldAndAppliedStagesAgreeAcrossProjections() {
        var r=release();var f=r.f();
        stage(f,ValidationReviewStage.REVIEW_COMPLETED);
        var held=inJob(r.job(),job->{var d=discovery(job,"에르웬");world(job,"엘프","서식지","북부");return stat(job,d.getProvisionalSubjectKey(),"에르웬",35,CharacterFactOperation.REVIEW_REQUIRED).getId();});
        completeExtraction(r);assertThat(applicationRow(f).get("status")).isEqualTo("HELD_REVIEW");
        stage(f,ValidationReviewStage.APPLICATION_HELD);
        var row=episodeRow(f);
        assertThat(row.analysisStatus()).as("existing field keeps its meaning").isEqualTo(EpisodeAnalysisStatus.ERROR_REVIEW_PENDING);assertThat(row.validationReportId()).isEqualTo(f.report());
        var card=batchCard(f);var group=card.episodeJobGroups().stream().filter(g->g.jobType()==AnalysisJobType.SETTING_EXTRACTION).findFirst().orElseThrow();
        assertThat(group.failedJobCount()).as("HELD release job is not a failed extraction").isZero();assertThat(group.succeededJobCount()).isEqualTo(1);
        assertThat(group.status()).isEqualTo(AnalysisBatchStatus.COMPLETED);
        assertThat(card.episodeStatus()).as("card status keeps its report-status meaning; the stage is in validationReports").isEqualTo(AnalysisBatchStatus.ERROR_REVIEW_PENDING);
        characterReview.dismissSettingCandidate(f.member(),f.work(),held);
        assertThat(apply(f,"after-dismiss").status()).isEqualTo(ValidationApplicationStatus.APPLIED);
        stage(f,ValidationReviewStage.APPLIED);
        var applied=batchCard(f).episodeJobGroups().stream().filter(g->g.jobType()==AnalysisJobType.SETTING_EXTRACTION).findFirst().orElseThrow();
        assertThat(applied.failedJobCount()).isZero();assertThat(applied.status()).isEqualTo(AnalysisBatchStatus.COMPLETED);
        var entry=history(f).getFirst();
        assertThat(entry.completion().trigger()).isEqualTo(ValidationReviewTrigger.AUTOMATIC);assertThat(entry.application().status()).isEqualTo(ValidationApplicationStatus.APPLIED);
        assertThat(entry.application().appliedAt()).isNotNull();assertThat(entry.invalidatedAt()).isNull();assertThat(entry.counts()).isEqualTo(new ValidationReportHistoryResponse.Counts(0,0));
    }

    @Test @DisplayName("판단 전·검토 종료 전에는 AWAITING_REVIEW, release Job 실패는 EXTRACTION_FAILED이며 목록도 같은 값이다")
    void awaitingReviewAndExtractionFailedStages() {
        var f=fixture();stage(f,ValidationReviewStage.VALIDATING);
        runToCompletion(f,true);stage(f,ValidationReviewStage.AWAITING_REVIEW);
        keepAllTypos(f);stage(f,ValidationReviewStage.AWAITING_REVIEW);
        var completion=completions.completeValidationReview(f.member(),f.work(),f.report(),new ValidationReviewCompletionRequest(reportVersion(f),"explicit"));
        stage(f,ValidationReviewStage.REVIEW_COMPLETED);
        var payload=analysisWorker.claimAnalysisJob(claimRequest()).orElseThrow();assertThat(payload.analysisJobId()).isEqualTo(completion.extractionJobId());
        analysisWorker.failAnalysisJob(payload.analysisJobId(),payload.leaseToken(),new WorkerAnalysisJobFailRequest(AnalysisFailureCode.LLM_PROVIDER_ERROR,"합성 실패"));
        stage(f,ValidationReviewStage.EXTRACTION_FAILED);
    }

    /** 한 비교 결정(MERGED + REVIEW_REQUIRED + SCOPE_UNRESOLVED)을 공유하는 두 세계관 후보. journal 변경은 ADD 경로로 덮는다. */
    List<UUID> sharedDecisionWorlds(org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob job,String subject) {
        var a=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(job.getWork(),job.getEpisode(),job,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,subject,"서식지","북부",JSON.arrayNode(),java.math.BigDecimal.ONE,null);
        var b=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(job.getWork(),job.getEpisode(),job,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,subject,"서식지","북쪽",JSON.arrayNode(),java.math.BigDecimal.ONE,null);
        em.persist(a);em.persist(b);String key="provisional-world:"+a.getId();
        for(var c:List.of(a,b))c.resolveOrderedSubject(org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSubjectResolutionType.NEW,key,subject,JSON.arrayNode(),JSON.arrayNode().add(key));
        var batch=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonBatch.createOrdered(job.getWork(),job.getEpisode(),job,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,null,
                org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSubjectResolutionType.NEW,key,subject,JSON.arrayNode(),JSON.arrayNode().add(key),2);em.persist(batch);
        a.startComparison(batch,"C1");b.startComparison(batch,"C2");
        var decision=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecision.create(batch,"D1",subject,null,null,null,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingConsolidationStatus.MERGED,
                org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation.REVIEW_REQUIRED,null,null,"서식지",null,"북부","원문 근거",null);
        decision.bindOrderedTarget(key,0L);em.persist(decision);
        for(var c:List.of(a,b)) {c.completeComparison(decision,java.time.LocalDateTime.now());
            org.springframework.test.util.ReflectionTestUtils.setField(c,"suggestedOperation",org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation.REVIEW_REQUIRED);
            org.springframework.test.util.ReflectionTestUtils.setField(c,"comparisonReviewReason",org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonReviewReason.SCOPE_UNRESOLVED);}
        em.persist(org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecisionSource.create(batch,decision,a,"C1",0));
        em.persist(org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecisionSource.create(batch,decision,b,"C2",1));batch.complete("d".repeat(64),JSON.objectNode());
        var target=JSON.objectNode();target.putNull("actualWorldSettingId");target.put("provisionalSubjectKey",key);target.put("category","RACE");target.put("subjectName",subject);
        target.put("normalizedSubjectName",subject);target.put("version",1);target.putObject("propertiesJson").put("서식지","북부");target.putObject("provenanceByPath");
        states.appendValidatedChanges(job,job.getInputStateHash(),List.of(new org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange("world-decision:"+decision.getId(),List.of("worldSettings",key),target,false,"ADD",List.of(a.getId(),b.getId()))));
        return List.of(a.getId(),b.getId(),job.getBatch().getId());
    }

    void draft(Fixture f,UUID batch,UUID candidate,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory category,String subject,String setting,String value) {
        worldReview.updateCandidateDecisions(f.member(),f.work(),new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateDecisionUpdateRequest(batch,List.of(
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateDecisionUpdateItem(candidate,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation.ADD,category,subject,null,setting,value,"대상 정리"))));
    }

    @Test @DisplayName("한 비교 결정의 두 후보를 결정 초안으로 다른 분류·대상에 나누면 반영은 GROUP_INVALID로 전부 되돌려 UNKNOWN_ERROR 보류이고, 대상을 맞추면 반영된다")
    void splitSharedDecisionHoldsAsUnknownErrorUntilRegrouped() {
        var r=release();var f=r.f();
        var ids=inJob(r.job(),job->sharedDecisionWorlds(job,"엘프"));UUID a=ids.get(0),b=ids.get(1),batch=ids.get(2);
        completeExtraction(r);assertThat(applicationRow(f).get("status")).isEqualTo("HELD_REVIEW");
        draft(f,batch,a,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,"엘프","서식지","북부");
        draft(f,batch,b,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.LOCATION,"북쪽 숲","서식 종족","엘프");
        var split=apply(f,"split");
        assertThat(split.status()).isEqualTo(ValidationApplicationStatus.HELD_REVIEW);assertThat(split.lastErrorCode()).isEqualTo("WORLD_SETTING_CANDIDATE_GROUP_INVALID");
        assertThat(split.holdReasons()).containsExactly(org.monitoring.catchholebackend.domain.validation.dto.response.ValidationApplicationHoldReason.episode(ValidationApplicationHoldCode.UNKNOWN_ERROR,"WORLD_SETTING_CANDIDATE_GROUP_INVALID"));
        assertThat(worldSettings(f)).isZero();assertThat(confirmed(f)).isZero();stage(f,ValidationReviewStage.APPLICATION_HELD);
        draft(f,batch,b,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,"엘프","겨울 서식지","북쪽");
        var regrouped=apply(f,"regrouped");
        assertThat(regrouped.status()).isEqualTo(ValidationApplicationStatus.APPLIED);assertThat(confirmed(f)).isEqualTo(2);
        assertThat(worldProperties(f,"엘프")).containsExactlyInAnyOrder(new org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.Property(null,"서식지","북부"),
                new org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.Property(null,"겨울 서식지","북쪽"));
        stage(f,ValidationReviewStage.APPLIED);
    }

    @Test @DisplayName("이전 report의 release Job이 순차 실패로 남은 채 기준 변경으로 재검수하면 그 Job을 무효화해 새 report의 release가 반영까지 진행된다")
    void recheckClosesStaleFailedReleaseJob() {
        var r=release();var f=r.f();
        analysisWorker.failAnalysisJob(r.job(),r.payload().leaseToken(),new WorkerAnalysisJobFailRequest(AnalysisFailureCode.LLM_PROVIDER_ERROR,"합성 실패"));
        assertThat(jobState(r.job())).startsWith("FAILED/");
        confirmedSettingsChanged(f);assertThat(reportStatus(f.report())).isEqualTo("INVALIDATED");
        var next=recheck(f,"after-failed-release");
        assertThat(jobState(r.job())).as("the stale release job is closed with the existing ordered invalidation").isEqualTo("FAILED/INVALIDATED");
        assertThatThrownBy(()->analysisJobsApi.retryFailedAnalysisJob(f.member(),f.work(),r.job())).as("the retired receipt's job cannot be resumed").isInstanceOf(AppException.class);
        var run=runToCompletion(next,false);var newJob=(UUID)run.done().get("extractionJobId");
        assertThat(newJob).as(String.valueOf(run.done())).isNotNull().isNotEqualTo(r.job());
        assertThat(jdbc.queryForObject("select release_hold_code from validation_reports where id=?",String.class,next.report())).isNull();
        var payload=analysisWorker.claimAnalysisJob(claimRequest()).orElseThrow();assertThat(payload.analysisJobId()).isEqualTo(newJob);
        inJob(newJob,job->{var d=discovery(job,"에르웬");stat(job,d.getProvisionalSubjectKey(),"에르웬",35,CharacterFactOperation.ADD);world(job,"엘프","서식지","북부");return null;});
        completeExtraction(newJob,payload.leaseToken());
        assertThat(applicationRow(next).get("status")).isEqualTo("APPLIED");assertThat(factValue(f,"에르웬")).isEqualTo("35");
        stage(next,ValidationReviewStage.APPLIED);
        var rows=history(next);assertThat(rows).extracting(ValidationReportHistoryResponse::reportId).containsExactly(next.report(),f.report());
        var old=rows.get(1);
        assertThat(old.current()).isFalse();assertThat(old.reviewStage()).isEqualTo(ValidationReviewStage.INVALIDATED);assertThat(old.invalidatedAt()).isNotNull();
        assertThat(old.completion()).as("retired receipt stays visible as history").isNotNull();assertThat(old.application()).isNull();
        assertThat(rows.getFirst().previousReportId()).isEqualTo(f.report());assertThat(rows.getFirst().revision()).isEqualTo(old.revision()+1);
    }
}
