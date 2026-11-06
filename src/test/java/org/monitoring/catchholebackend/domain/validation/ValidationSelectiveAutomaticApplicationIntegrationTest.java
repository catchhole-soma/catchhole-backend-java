package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.dto.request.*;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.*;
import org.monitoring.catchholebackend.domain.worldsetting.type.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(properties="spring.config.import=") @ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@DisplayName("검수 후 추출도 일반 추출과 같은 선택적 자동 반영")
class ValidationSelectiveAutomaticApplicationIntegrationTest extends ValidationSettingsApplicationFixture {
    @BeforeEach void chunks() {jdbc.execute("create table if not exists episode_chunks (id uuid primary key, episode_id uuid)");}

    @Test @DisplayName("캐릭터 검토 필요·세계관 범위 미정이 있어도 정상 후보는 반영하고 남은 후보는 직접 확정할 수 있다")
    void appliesEligibleCandidatesAndAllowsManualReviewOfRemainder() {
        var r=release();var f=r.f();
        var ids=inJob(r.job(),job->{
            var d=discovery(job,"에르웬");stat(job,d.getProvisionalSubjectKey(),"에르웬",35,CharacterFactOperation.ADD);
            var d2=discovery(job,"비요른");var held=stat(job,d2.getProvisionalSubjectKey(),"비요른",50,CharacterFactOperation.REVIEW_REQUIRED);
            world(job,"엘프","서식지","북부");var scope=scopeUnresolvedWorld(job,"드워프","서식지","산");
            return List.of(held.getId(),scope.getId(),job.getBatch().getId());
        });
        completeExtraction(r);
        assertThat(confirmed(f)).isEqualTo(4);assertThat(characters(f)).isEqualTo(2);
        assertThat(facts(f)).isEqualTo(1);assertThat(worldSettings(f)).isEqualTo(1);
        assertThat(applicationRow(f).get("status")).isEqualTo("APPLIED");
        assertThat(automaticAppliedAt(r.job())).isNotNull();assertThat(heldAt(r.job())).isNull();
        assertThat(reportStatus(f.report())).isEqualTo("WAITING_FOR_ERROR_REVIEW");
        assertThat(tx.<Boolean>execute(s->em.find(SettingCandidate.class,ids.get(0)).isManualReviewAvailable())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from setting_candidates where analysis_job_id=? and review_status='PENDING_REVIEW'",Long.class,r.job())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from world_setting_candidates where analysis_job_id=? and review_status='PENDING_REVIEW'",Long.class,r.job())).isEqualTo(1);

        characterReview.confirmSettingCandidateGroup(f.member(),f.work(),new SettingCandidateGroupConfirmRequest(ids.get(2),List.of(
                new SettingCandidateGroupConfirmDecision(ids.get(0),CharacterFactConfirmApplicationMode.HISTORY_ONLY,null))));
        worldReview.updateCandidateDecisions(f.member(),f.work(),new WorldSettingCandidateDecisionUpdateRequest(ids.get(2),List.of(
                new WorldSettingCandidateDecisionUpdateItem(ids.get(1),WorldSettingOperation.ADD,WorldSettingCategory.RACE,"드워프","깊은 곳","서식지","산","범위 확인"))));
        var result=worldReview.confirmCandidateGroup(f.member(),f.work(),new WorldSettingCandidateGroupConfirmRequest(ids.get(2),List.of(
                new WorldSettingCandidateGroupConfirmRequest.Decision(ids.get(1),WorldSettingOperation.ADD,WorldSettingCategory.RACE,"드워프","깊은 곳","서식지","산",true,"범위 확인"))));
        assertThat(result.recomparisonRequired()).isFalse();
        assertThat(confirmed(f)).isEqualTo(6);assertThat(facts(f)).isEqualTo(2);assertThat(worldSettings(f)).isEqualTo(2);
        assertThat(reportStatus(f.report())).isEqualTo("WAITING_FOR_ERROR_REVIEW");
    }

    @Test @DisplayName("값 형식이 틀린 후보만 남기고 정상 인물 발견·세계관 후보는 반영한다")
    void invalidValueDoesNotBlockOtherCandidates() {
        var r=release();var f=r.f();
        var invalid=inJob(r.job(),job->{var d=discovery(job,"에르웬");var c=stat(job,d.getProvisionalSubjectKey(),"에르웬",35,CharacterFactOperation.ADD);
            ReflectionTestUtils.setField(c,"attributeValue","많음");ReflectionTestUtils.setField(c,"valueJson",JSON.objectNode().put("value","많음"));
            world(job,"엘프","서식지","북부");return c.getId();});
        completeExtraction(r);
        assertThat(confirmed(f)).isEqualTo(2);assertThat(characters(f)).isEqualTo(1);assertThat(facts(f)).isZero();assertThat(worldSettings(f)).isEqualTo(1);
        assertThat(tx.<Boolean>execute(s->em.find(SettingCandidate.class,invalid).isPendingReview())).isTrue();
        assertThat(automaticAppliedAt(r.job())).isNotNull();assertThat(applicationRow(f).get("status")).isEqualTo("APPLIED");
        assertThat(apply(f,"duplicate").attempt()).isEqualTo(1);assertThat(confirmed(f)).isEqualTo(2);
    }

    @Test @DisplayName("무효화된 리포트는 후보별 선택적 자동 반영으로 우회하지 못하고 전체 보류한다")
    void invalidatedReportStillBlocksAllApplication() {
        var r=release();var f=r.f();applicable(r);
        tx.executeWithoutResult(s->em.find(org.monitoring.catchholebackend.domain.validation.entity.ValidationReport.class,f.report()).purge());
        completeExtraction(r);
        assertThat(confirmed(f)).isZero();assertThat(characters(f)).isZero();assertThat(worldSettings(f)).isZero();
        assertThat(automaticAppliedAt(r.job())).isNull();assertThat(applicationRow(f).get("status")).isEqualTo("HELD_REVIEW");
        assertThat(reviews.getValidationReview(f.member(),f.work(),f.report()).application().holdReasons())
                .extracting(org.monitoring.catchholebackend.domain.validation.dto.response.ValidationApplicationHoldReason::code)
                .contains(ValidationApplicationHoldCode.REPORT_INVALIDATED);
    }

    @Test @DisplayName("자동 처리 뒤 진단 재검수를 접수해도 이전 추출에서 남은 후보를 직접 확정할 수 있다")
    void diagnosticRecheckDoesNotBlockRemainingCandidateReview() {
        var r=release();var f=r.f();
        var ids=inJob(r.job(),job->List.of(scopeUnresolvedWorld(job,"엘프","서식지","북부").getId(),job.getBatch().getId()));
        completeExtraction(r);
        var next=recheck(f,"diagnostic-with-pending-candidates");
        assertThat(admissions.get(f.member(),f.work(),next.report()).mode()).isEqualTo(ValidationReportMode.DIAGNOSTIC_ONLY);
        worldReview.updateCandidateDecisions(f.member(),f.work(),new WorldSettingCandidateDecisionUpdateRequest(ids.get(1),List.of(
                new WorldSettingCandidateDecisionUpdateItem(ids.get(0),WorldSettingOperation.ADD,WorldSettingCategory.RACE,"엘프","북부 숲","서식지","북부","범위 확인"))));
        assertThatThrownBy(()->worldReview.confirmCandidateGroup(f.member(),f.work(),new WorldSettingCandidateGroupConfirmRequest(ids.get(1),List.of(
                new WorldSettingCandidateGroupConfirmRequest.Decision(ids.get(0),WorldSettingOperation.ADD,WorldSettingCategory.RACE,"엘프","북부 숲","서식지","북부",true,"범위 확인")))))
                .isInstanceOfSatisfying(org.monitoring.catchholebackend.global.exception.AppException.class,
                        e->assertThat(e.getResultCode().getCode()).isEqualTo("VALIDATION_SETTINGS_LOCKED"));
        runToCompletion(next,false);
        var result=worldReview.confirmCandidateGroup(f.member(),f.work(),new WorldSettingCandidateGroupConfirmRequest(ids.get(1),List.of(
                new WorldSettingCandidateGroupConfirmRequest.Decision(ids.get(0),WorldSettingOperation.ADD,WorldSettingCategory.RACE,"엘프","북부 숲","서식지","북부",true,"범위 확인"))));
        assertThat(result.recomparisonRequired()).isFalse();assertThat(confirmed(f)).isEqualTo(1);assertThat(worldSettings(f)).isEqualTo(1);
    }
}
