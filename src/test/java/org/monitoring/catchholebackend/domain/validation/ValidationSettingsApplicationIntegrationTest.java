package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.character.dto.request.*;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.type.*;
import org.monitoring.catchholebackend.domain.validation.dto.request.*;
import org.monitoring.catchholebackend.domain.validation.dto.response.*;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.*;
import org.monitoring.catchholebackend.domain.worldsetting.entity.*;
import org.monitoring.catchholebackend.domain.worldsetting.type.*;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * GH312 S3 회차 전체 확정. 테스트 트랜잭션 없이 실제 커밋 경계로 release Job을 claim·완료한다. Worker claim은 DB 전체의 대기 Job을 고르므로
 * 앞 테스트가 남긴 대기 검수·추출 Job과 섞이지 않게 메서드마다 새 Context/H2를 사용한다.
 */
@SpringBootTest(properties="spring.config.import=") @ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@DisplayName("GH312 S3 검토 완료 회차의 설정 전체 확정")
class ValidationSettingsApplicationIntegrationTest extends ValidationSettingsApplicationFixture {
    @BeforeEach void chunks() {jdbc.execute("create table if not exists episode_chunks (id uuid primary key, episode_id uuid)");}

    static String code(Throwable e) {return ((AppException)e).getResultCode().getCode();}
    static void blocked(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(AppException.class).extracting(ValidationSettingsApplicationIntegrationTest::code).isEqualTo("VALIDATION_RELEASE_MANUAL_CONFIRM_BLOCKED");
    }

    @Test @DisplayName("정상 0건 검수여도 값 형식이 틀린 후보가 있으면 자동 경로는 보류만 기록하고 아무 설정도 반영하지 않는다")
    void zeroFindingsButInvalidCandidateDoesNotApply() {
        var r=release();
        var invalid=inJob(r.job(),job->{var d=discovery(job,"에르웬");var s=stat(job,d.getProvisionalSubjectKey(),"에르웬",35,CharacterFactOperation.ADD);world(job,"엘프","서식지","북부");
            // 비교는 정상 완료였지만 현재 schema(NUMBER)와 맞지 않는 값이다. 조회의 valueValidation INVALID와 같은 판정이다.
            ReflectionTestUtils.setField(s,"attributeValue","많음");ReflectionTestUtils.setField(s,"valueJson",JSON.objectNode().put("value","많음"));return s.getId();});
        completeExtraction(r);
        var f=r.f();var row=applicationRow(f);
        assertThat(row.get("status")).isEqualTo("HELD_REVIEW");assertThat(((Number)row.get("attempt")).longValue()).isEqualTo(1);assertThat(row.get("trigger")).isEqualTo("AUTOMATIC");
        assertThat(row.get("applied_at")).isNull();assertThat(row.get("last_error_code")).isNull();
        var review=reviews.getValidationReview(f.member(),f.work(),f.report());
        assertThat(review.application().status()).isEqualTo(ValidationApplicationStatus.HELD_REVIEW);
        assertThat(review.application().holdReasons()).contains(new ValidationApplicationHoldReason(ValidationApplicationHoldCode.VALUE_INVALID,ValidationApplicationCandidateType.CHARACTER,invalid,"SETTING_CANDIDATE_VALUE_FORMAT_INVALID"))
                .as("only the invalid candidate holds; the others were applicable").hasSize(1);
        assertThat(review.allowedActions()).contains(ValidationReviewAction.APPLY_SETTINGS);assertThat(review.extractionState()).isEqualTo(ValidationExtractionState.SUCCEEDED);
        assertThat(confirmed(f)).isZero();assertThat(characters(f)).isZero();assertThat(facts(f)).isZero();assertThat(worldSettings(f)).isZero();
        assertThat(jobState(r.job())).as("extraction succeeded and stays sealed").isEqualTo("SUCCEEDED/SEALED");assertThat(automaticAppliedAt(r.job())).as("not marked applied").isNull();
        var staging=admissions.get(f.member(),f.work(),f.report());
        assertThat(staging.candidateStatus()).isEqualTo(ValidationPreparationStatus.PENDING);assertThat(staging.extractionStatus()).isEqualTo(ValidationPreparationStatus.READY);
        // 기존 검토 화면 기준으로 자동 반영 대기가 아니며(SUCCEEDED) 직접 확인 수에 포함된다.
        assertThat(tx.<Boolean>execute(s->em.find(SettingCandidate.class,invalid).isAutomaticApplicationPending())).isFalse();
    }

    @Test @DisplayName("모두 반영 가능한 캐릭터·세계관 후보는 한 트랜잭션에서 함께 확정되고 receipt는 APPLIED다")
    void readyPlanAppliesBothDomainsInOneTransaction() {
        var r=release();var ids=applicable(r);completeExtraction(r);var f=r.f();
        var row=applicationRow(f);
        assertThat(row.get("status")).isEqualTo("APPLIED");assertThat(row.get("applied_at")).isNotNull();assertThat(row.get("hold_reasons")).isNull();
        assertThat(((Number)row.get("attempt")).longValue()).isEqualTo(1);assertThat(row.get("trigger")).isEqualTo("AUTOMATIC");
        long generation=jdbc.queryForObject("select extraction_job_generation from validation_review_completions where report_id=?",Long.class,f.report());
        assertThat(((Number)row.get("extraction_job_generation")).longValue()).isEqualTo(generation);
        assertThat(characters(f)).isEqualTo(1);assertThat(facts(f)).isEqualTo(1);assertThat(snapshotSources(f)).isEqualTo(1);assertThat(worldSettings(f)).isEqualTo(1);
        assertThat(confirmed(f)).isEqualTo(3);assertThat(automaticAppliedAt(r.job())).isNotNull();assertThat(jobState(r.job())).isEqualTo("SUCCEEDED/SEALED");
        assertThat(tx.<Boolean>execute(s->em.find(SettingCandidate.class,ids.stat()).isReviewedAutomatically())).isTrue();
        var review=reviews.getValidationReview(f.member(),f.work(),f.report());
        assertThat(review.application().status()).isEqualTo(ValidationApplicationStatus.APPLIED);assertThat(review.application().appliedAt()).isNotNull();
        assertThat(review.application().extractionJobGeneration()).isEqualTo(generation);assertThat(review.allowedActions()).doesNotContain(ValidationReviewAction.APPLY_SETTINGS);
        assertThat(review.extractionState()).isEqualTo(ValidationExtractionState.SUCCEEDED);
        assertThat(admissions.get(f.member(),f.work(),f.report()).candidateStatus()).isEqualTo(ValidationPreparationStatus.READY);
        // 확정 설정 변경은 기존 정책대로 이 회차의 검수 기준 복사본도 무효화한다. 다음 재검수가 진단 전용이 되는 근거다.
        assertThat(jdbc.queryForObject("select status from validation_reports where id=?",String.class,f.report())).isEqualTo("INVALIDATED");
    }

    @Test @DisplayName("검수 회차는 개별·그룹 확정·승격·세계관 확정을 409로 막고 수정·연결·제외는 허용한다")
    void manualConfirmCannotBypassGate() {
        var r=release();var f=r.f();
        var ids=inJob(r.job(),job->{var d=discovery(job,"에르웬");var s=stat(job,d.getProvisionalSubjectKey(),"에르웬",35,CharacterFactOperation.REVIEW_REQUIRED);
            var w=world(job,"엘프","서식지","북부");var w2=world(job,"드워프","서식지","산");return List.of(d.getId(),s.getId(),w.getId(),w2.getId(),job.getBatch().getId());});
        completeExtraction(r);assertThat(applicationRow(f).get("status")).isEqualTo("HELD_REVIEW");
        UUID discovery=ids.get(0),stat=ids.get(1),world=ids.get(2),world2=ids.get(3),batch=ids.get(4);
        blocked(()->characterReview.confirmSettingCandidate(f.member(),f.work(),stat,new SettingCandidateConfirmRequest(CharacterFactConfirmApplicationMode.HISTORY_ONLY,null)));
        blocked(()->characterReview.confirmSettingCandidateGroup(f.member(),f.work(),new SettingCandidateGroupConfirmRequest(batch,List.of(
                new SettingCandidateGroupConfirmDecision(discovery,CharacterFactConfirmApplicationMode.APPLY_PROPOSAL,null),
                new SettingCandidateGroupConfirmDecision(stat,CharacterFactConfirmApplicationMode.HISTORY_ONLY,null)))));
        blocked(()->tx.executeWithoutResult(s->promotions.promote(em.find(SettingCandidate.class,stat),CharacterFactConfirmApplicationMode.HISTORY_ONLY)));
        blocked(()->tx.executeWithoutResult(s->promotions.promoteNewCharacterGroup(List.of(new org.monitoring.catchholebackend.domain.character.service.SettingCandidateGroupPromotion(
                em.find(SettingCandidate.class,discovery),CharacterFactConfirmApplicationMode.APPLY_PROPOSAL)))));
        var updatedAt=tx.execute(s->em.find(SettingCandidate.class,stat).getUpdatedAt());
        blocked(()->characterReview.updateSettingCandidate(f.member(),f.work(),stat,new SettingCandidateUpdateRequest("stats.mental","35",CharacterFactConfirmApplicationMode.HISTORY_ONLY,updatedAt)));
        blocked(()->worldReview.confirmCandidate(f.member(),f.work(),world,new WorldSettingCandidateConfirmRequest(WorldSettingOperation.ADD,WorldSettingCategory.RACE,"엘프",null,"서식지","북부",false,null)));
        blocked(()->worldReview.confirmCandidateGroup(f.member(),f.work(),new WorldSettingCandidateGroupConfirmRequest(batch,List.of(
                new WorldSettingCandidateGroupConfirmRequest.Decision(world,WorldSettingOperation.ADD,WorldSettingCategory.RACE,"엘프",null,"서식지","북부",false,null)))));
        assertThat(confirmed(f)).isZero();assertThat(characters(f)).isZero();assertThat(worldSettings(f)).isZero();
        // 확정 설정을 쓰지 않는 경로는 그대로 열려 있어 작가가 보류를 해소할 수 있다.
        characterReview.updateSettingCandidate(f.member(),f.work(),stat,new SettingCandidateUpdateRequest("stats.mental","36"));
        characterReview.updateSettingCandidateCharacterMatch(f.member(),f.work(),stat,new SettingCandidateCharacterMatchRequest(SettingCandidateCharacterMatchResolutionType.CREATE_NEW,null,"에르웬"));
        worldReview.updateCandidateDecisions(f.member(),f.work(),new WorldSettingCandidateDecisionUpdateRequest(batch,List.of(
                new WorldSettingCandidateDecisionUpdateItem(world2,WorldSettingOperation.ADD,WorldSettingCategory.RACE,"드워프",null,"서식지","깊은 산","작가 확인"))));
        characterReview.dismissSettingCandidate(f.member(),f.work(),stat);
        worldReview.dismissCandidate(f.member(),f.work(),world,new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateDismissRequest("작가 제외"));
        assertThat(confirmed(f)).isZero();assertThat(worldSettings(f)).isZero();
        // 작가가 저장한 세계관 최종 결정은 S3의 회차 전체 확정이 반영하지 않는 보류 사유로 남는다.
        var held=apply(f,"after-edits");
        assertThat(held.status()).isEqualTo(ValidationApplicationStatus.HELD_REVIEW);
        assertThat(held.holdReasons()).extracting(ValidationApplicationHoldReason::code,ValidationApplicationHoldReason::candidateId)
                .containsExactly(tuple(ValidationApplicationHoldCode.AUTHOR_EDIT_PENDING,world2));
        assertThat(confirmed(f)).isZero();
    }

    @Test @DisplayName("일반 회차는 기존 자동 반영 contributor로 확정되고 사람의 그룹 확정도 그대로 동작한다")
    void releaseOnlyPathDoesNotChangeLegacyAutomaticApply() {
        var f=fixture();
        // 검수 report가 없는 앞 회차(100화)의 일반 자동 분석. 101화의 검수 report는 이 회차의 판단에 쓰지 않는다.
        var job=tx.execute(s->{
            var work=em.find(Work.class,f.work());var episode=em.createQuery("select e from Episode e where e.work=:w and e.episodeNo=100",Episode.class).setParameter("w",work).getSingleResult();
            var batch=UploadBatch.create(work,work.getMember(),UploadType.MULTI_EPISODE_MULTI_FILE,UploadSourceType.FILE);em.persist(batch);
            var created=AnalysisJob.create(work,batch,episode,AnalysisJobType.SETTING_EXTRACTION);created.configureReviewMode(AnalysisReviewMode.AUTOMATIC);states.initializeRun(List.of(created));return created.getId();
        });
        var payload=analysisWorker.claimAnalysisJob(claimRequest()).orElseThrow();assertThat(payload.analysisJobId()).isEqualTo(job);
        var pending=inJob(job,j->{world(j,"드워프","서식지","산");
            var c=WorldSettingCandidate.create(j.getWork(),j.getEpisode(),j,WorldSettingCategory.RACE,"엘프","서식지","남부",JSON.arrayNode(),BigDecimal.ONE,null);em.persist(c);
            String key="provisional-world:"+c.getId();c.resolveOrderedSubject(WorldSettingSubjectResolutionType.NEW,key,"엘프",JSON.arrayNode(),JSON.arrayNode().add(key));
            var comparison=WorldSettingComparisonBatch.createOrdered(j.getWork(),j.getEpisode(),j,WorldSettingCategory.RACE,null,WorldSettingSubjectResolutionType.NEW,key,"엘프",JSON.arrayNode(),JSON.arrayNode().add(key),1);em.persist(comparison);
            c.startComparison(comparison,"C1");comparison.recordContext(JSON.objectNode().putObject("targets").putObject(key));
            c.failComparison(AnalysisFailureCode.LLM_RESPONSE_PARSE_ERROR,"직접 확인 필요");comparison.fail(AnalysisFailureCode.LLM_RESPONSE_PARSE_ERROR,"직접 확인 필요");return c.getId();});
        completeExtraction(job,payload.leaseToken());
        assertThat(automaticAppliedAt(job)).as("contributors finished automatic application").isNotNull();assertThat(worldSettings(f)).isEqualTo(1);
        assertThat(applicationRows(f)).as("no whole-episode receipt for a non-validation episode").isZero();
        var batch=inJob(job,j->j.getBatch().getId());
        worldReview.updateCandidateDecisions(f.member(),f.work(),new WorldSettingCandidateDecisionUpdateRequest(batch,List.of(
                new WorldSettingCandidateDecisionUpdateItem(pending,WorldSettingOperation.ADD,WorldSettingCategory.RACE,"엘프",null,"서식지","남부","직접 확인"))));
        var result=worldReview.confirmCandidateGroup(f.member(),f.work(),new WorldSettingCandidateGroupConfirmRequest(batch,List.of(
                new WorldSettingCandidateGroupConfirmRequest.Decision(pending,WorldSettingOperation.ADD,WorldSettingCategory.RACE,"엘프",null,"서식지","남부",true,"직접 확인"))));
        assertThat(result.recomparisonRequired()).isFalse();assertThat(worldSettings(f)).isEqualTo(2);
    }

    @Test @DisplayName("보류 뒤 작가가 허용된 제외로 해소하면 명시 반영이 두 번째 시도로 APPLIED가 된다")
    void heldThenExplicitApplyAfterResolution() {
        var r=release();var f=r.f();
        var review=inJob(r.job(),job->{var d=discovery(job,"에르웬");stat(job,d.getProvisionalSubjectKey(),"에르웬",35,CharacterFactOperation.ADD);
            var d2=discovery(job,"비요른");var held=stat(job,d2.getProvisionalSubjectKey(),"비요른",50,CharacterFactOperation.REVIEW_REQUIRED);world(job,"엘프","서식지","북부");return held.getId();});
        completeExtraction(r);
        var summary=reviews.getValidationReview(f.member(),f.work(),f.report());
        assertThat(summary.application().holdReasons()).extracting(ValidationApplicationHoldReason::code,ValidationApplicationHoldReason::candidateId)
                .containsExactly(tuple(ValidationApplicationHoldCode.REVIEW_REQUIRED,review));
        assertThat(confirmed(f)).isZero();
        characterReview.dismissSettingCandidate(f.member(),f.work(),review);
        var applied=apply(f,"after-dismiss");
        assertThat(applied.status()).isEqualTo(ValidationApplicationStatus.APPLIED);assertThat(applied.attempt()).isEqualTo(2);assertThat(applied.trigger()).isEqualTo(ValidationReviewTrigger.EXPLICIT);
        assertThat(applied.holdReasons()).isEmpty();assertThat(applied.extractionJobId()).isEqualTo(r.job());assertThat(applied.reportVersion()).isEqualTo(reportVersion(f));
        assertThat(characters(f)).isEqualTo(2);assertThat(facts(f)).isEqualTo(1);assertThat(worldSettings(f)).isEqualTo(1);assertThat(automaticAppliedAt(r.job())).isNotNull();
        assertThat(reviews.getValidationReview(f.member(),f.work(),f.report()).application().status()).isEqualTo(ValidationApplicationStatus.APPLIED);
    }

    @Test @DisplayName("같은 키 재전송은 같은 receipt이고 APPLIED 뒤 다른 키도 같은 APPLIED receipt를 시도 증가 없이 돌려준다")
    void duplicateApplyReturnsSameReceipt() {
        var r=release();var f=r.f();
        var held=inJob(r.job(),job->{var d=discovery(job,"에르웬");world(job,"엘프","서식지","북부");return stat(job,d.getProvisionalSubjectKey(),"에르웬",35,CharacterFactOperation.REVIEW_REQUIRED).getId();});
        completeExtraction(r);
        long version=reportVersion(f);var request=new ValidationSettingsApplicationRequest(version,"tab-a");
        var first=applications.applySettings(f.member(),f.work(),f.report(),request);
        assertThat(first.status()).isEqualTo(ValidationApplicationStatus.HELD_REVIEW);assertThat(first.attempt()).isEqualTo(2);
        assertThat(applications.applySettings(f.member(),f.work(),f.report(),request)).isEqualTo(first);assertThat(reportVersion(f)).isEqualTo(first.reportVersion());
        assertThatThrownBy(()->applications.applySettings(f.member(),f.work(),f.report(),new ValidationSettingsApplicationRequest(version+1,"tab-a")))
                .extracting(ValidationSettingsApplicationIntegrationTest::code).isEqualTo("VALIDATION_IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(()->applications.applySettings(f.member(),f.work(),f.report(),new ValidationSettingsApplicationRequest(version,"tab-b")))
                .extracting(ValidationSettingsApplicationIntegrationTest::code).isEqualTo("VALIDATION_REPORT_VERSION_CONFLICT");
        characterReview.dismissSettingCandidate(f.member(),f.work(),held);
        var applyRequest=new ValidationSettingsApplicationRequest(reportVersion(f),"tab-c");var applied=applications.applySettings(f.member(),f.work(),f.report(),applyRequest);
        assertThat(applied.status()).isEqualTo(ValidationApplicationStatus.APPLIED);assertThat(applied.attempt()).isEqualTo(3);
        long after=reportVersion(f);
        assertThat(applications.applySettings(f.member(),f.work(),f.report(),applyRequest)).isEqualTo(applied);
        var other=applications.applySettings(f.member(),f.work(),f.report(),new ValidationSettingsApplicationRequest(0L,"tab-d"));
        assertThat(other).as("same APPLIED receipt, no new attempt").isEqualTo(applied);assertThat(reportVersion(f)).isEqualTo(after);
        assertThat(applicationRows(f)).isEqualTo(1);assertThat(characters(f)).isEqualTo(1);assertThat(worldSettings(f)).isEqualTo(1);
    }

    @Test @DisplayName("추출·비교가 진행 중이면 명시 반영은 NOT_READY 409이고 receipt를 만들지 않는다")
    void notReadyWhileComparisonsRun() {
        var f=fixture();var run=runToCompletion(f,false);assertThat(run.done().get("extractionJobId")).isNotNull();
        assertThatThrownBy(()->apply(f,"queued")).extracting(ValidationSettingsApplicationIntegrationTest::code).isEqualTo("VALIDATION_APPLICATION_NOT_READY");
        var payload=analysisWorker.claimAnalysisJob(claimRequest()).orElseThrow();
        assertThatThrownBy(()->apply(f,"running")).extracting(ValidationSettingsApplicationIntegrationTest::code).isEqualTo("VALIDATION_APPLICATION_NOT_READY");
        assertThat(applicationRows(f)).isZero();assertThat(reviews.getValidationReview(f.member(),f.work(),f.report()).application()).isNull();
        assertThat(reviews.getValidationReview(f.member(),f.work(),f.report()).extractionState()).isEqualTo(ValidationExtractionState.RUNNING);
        assertThat(admissions.get(f.member(),f.work(),f.report()).candidateStatus()).isEqualTo(ValidationPreparationStatus.PENDING);
        assertThat(payload.analysisJobId()).isNotNull();
    }

    @Test @DisplayName("검토 종료 전 report의 반영 요청은 UNAVAILABLE이다")
    void applicationRequiresReviewCompletion() {
        var f=fixture();runToCompletion(f,true);
        assertThatThrownBy(()->apply(f,"early")).extracting(ValidationSettingsApplicationIntegrationTest::code).isEqualTo("VALIDATION_APPLICATION_UNAVAILABLE");
        assertThat(applicationRows(f)).isZero();
    }

    @Test @DisplayName("APPLIED 뒤 재검수는 자동 반영 이력의 진단 전용이고 새 검토 종료·추출을 만들 수 없다")
    void appliedEpisodeRecheckIsDiagnosticOnly() {
        var r=release();applicable(r);completeExtraction(r);var f=r.f();assertThat(applicationRow(f).get("status")).isEqualTo("APPLIED");
        var next=recheck(f,"after-applied");
        var staging=admissions.get(f.member(),f.work(),next.report());
        assertThat(staging.mode()).isEqualTo(ValidationReportMode.DIAGNOSTIC_ONLY);
        assertThat(staging.previousApplicationReceipt().kind()).isEqualTo("AUTOMATIC_APPLICATION");assertThat(staging.previousApplicationReceipt().analysisJobId()).isEqualTo(r.job());
        assertThat(staging.candidateStatus()).isEqualTo(ValidationPreparationStatus.NOT_STARTED);
        assertThatThrownBy(()->completions.completeValidationReview(f.member(),f.work(),next.report(),new ValidationReviewCompletionRequest(reportVersion(next),"diagnostic")))
                .extracting(ValidationSettingsApplicationIntegrationTest::code).isEqualTo("VALIDATION_REVIEW_NOT_COMPLETABLE");
        assertThatThrownBy(()->apply(next,"diagnostic-apply")).extracting(ValidationSettingsApplicationIntegrationTest::code).isEqualTo("VALIDATION_APPLICATION_UNAVAILABLE");
        assertThatThrownBy(()->apply(f,"retired-apply")).extracting(ValidationSettingsApplicationIntegrationTest::code).isEqualTo("VALIDATION_APPLICATION_UNAVAILABLE");
        assertThat(analysisJobs(f)).isEqualTo(1);assertThat(applicationRows(f)).as("retired report keeps its receipt as history").isEqualTo(1);
        assertThat(reviews.getValidationReview(f.member(),f.work(),f.report()).application()).as("retired report's receipt is not read").isNull();
    }
}
