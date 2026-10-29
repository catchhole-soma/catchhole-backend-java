package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationApplicationHoldReason;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.domain.worldsetting.exception.WorldSettingErrorCode;
import org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingCandidateServiceImpl;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * GH312 S3 회차 전체 확정의 되돌림. 테스트 트랜잭션 없이 실제 커밋 경계를 쓰며 세계관 반영 실패는 테스트 spy로만 만든다(운영 플래그 없음).
 * 캐릭터 쓰기가 먼저 실행된 뒤 세계관이 실패해도 두 도메인 모두 원상태이고 추출 완료와 보류 사유만 별도 트랜잭션에 남아야 한다.
 */
@SpringBootTest(properties="spring.config.import=") @ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@DisplayName("GH312 S3 회차 전체 확정 실패의 전체 되돌림")
class ValidationSettingsApplicationRollbackIntegrationTest extends ValidationSettingsApplicationFixture {
    @MockitoSpyBean WorldSettingCandidateServiceImpl worldApplication;
    @BeforeEach void chunks() {jdbc.execute("create table if not exists episode_chunks (id uuid primary key, episode_id uuid)");}

    void assertNothingApplied(Release r) {
        var f=r.f();
        assertThat(characters(f)).as("character created by the first domain is rolled back").isZero();
        assertThat(facts(f)).isZero();assertThat(snapshotSources(f)).isZero();assertThat(worldSettings(f)).isZero();assertThat(confirmed(f)).isZero();
        assertThat(automaticAppliedAt(r.job())).isNull();
        assertThat(jdbc.queryForObject("select count(*) from setting_candidates where analysis_job_id=? and (reviewed_automatically or automatic_review_hold_reason is not null)",Long.class,r.job()))
                .as("contributor hold marks are rolled back too").isZero();
    }

    @Test @DisplayName("캐릭터 반영 뒤 세계관 반영이 실패하면 캐릭터 쓰기까지 되돌리고 추출 완료와 UNKNOWN_ERROR 보류만 남긴다")
    void worldFailureRollsBackCharacterWrites() {
        var r=release();applicable(r);
        doThrow(new IllegalStateException("합성 저장 실패")).when(worldApplication).applyAutomatically(any());
        completeExtraction(r);
        assertNothingApplied(r);
        assertThat(jobState(r.job())).as("extraction completion is kept by the second transaction").isEqualTo("SUCCEEDED/SEALED");
        var row=applicationRow(r.f());
        assertThat(row.get("status")).isEqualTo("HELD_REVIEW");assertThat(row.get("last_error_code")).isEqualTo("UNEXPECTED_ERROR");assertThat(((Number)row.get("attempt")).longValue()).isEqualTo(1);
        var review=reviews.getValidationReview(r.f().member(),r.f().work(),r.f().report());
        assertThat(review.application().holdReasons()).containsExactly(ValidationApplicationHoldReason.episode(ValidationApplicationHoldCode.UNKNOWN_ERROR,"UNEXPECTED_ERROR"));
        assertThat(jdbc.queryForObject("select status from validation_reports where id=?",String.class,r.f().report())).as("no confirmed-setting invalidation survived").isEqualTo("WAITING_FOR_ERROR_REVIEW");
        // 실패 원인이 사라지면 작가의 명시 반영으로 같은 계획을 두 도메인 함께 반영한다.
        reset(worldApplication);
        var applied=apply(r.f(),"after-failure");
        assertThat(applied.status()).isEqualTo(ValidationApplicationStatus.APPLIED);assertThat(applied.attempt()).isEqualTo(2);assertThat(applied.lastErrorCode()).isNull();
        assertThat(characters(r.f())).isEqualTo(1);assertThat(worldSettings(r.f())).isEqualTo(1);assertThat(automaticAppliedAt(r.job())).isNotNull();
    }

    @Test @DisplayName("작가가 결정한 세계관 후보의 반영이 실패하면 먼저 쓴 캐릭터까지 되돌리고 결정 초안은 남긴다")
    void decidedWorldFailureRollsBackCharacterWrites() {
        var r=release();
        var ids=inJob(r.job(),job->{var d=discovery(job,"에르웬");stat(job,d.getProvisionalSubjectKey(),"에르웬",35,CharacterFactOperation.ADD);
            return List.of(scopeUnresolvedWorld(job,"엘프","서식지","북부").getId(),job.getBatch().getId());});
        completeExtraction(r);assertThat(applicationRow(r.f()).get("status")).isEqualTo("HELD_REVIEW");
        worldApplication.updateCandidateDecisions(r.f().member(),r.f().work(),new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateDecisionUpdateRequest(ids.get(1),List.of(
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateDecisionUpdateItem(ids.get(0),org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation.ADD,
                        org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,"엘프","북부 숲","서식지","북부","범위 확인"))));
        doThrow(new IllegalStateException("합성 결정 반영 실패")).when(worldApplication).applyReviewedDecisions(any());
        var held=apply(r.f(),"decided-failure");
        assertThat(held.status()).isEqualTo(ValidationApplicationStatus.HELD_REVIEW);assertThat(held.lastErrorCode()).isEqualTo("UNEXPECTED_ERROR");
        assertNothingApplied(r);
        assertThat(jdbc.queryForObject("select final_operation from world_setting_candidates where id=?",String.class,ids.get(0))).as("the saved decision survives the rollback").isEqualTo("ADD");
        reset(worldApplication);
        assertThat(apply(r.f(),"decided-retry").status()).isEqualTo(ValidationApplicationStatus.APPLIED);
        assertThat(characters(r.f())).isEqualTo(1);assertThat(worldProperties(r.f(),"엘프")).containsExactly(new org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.Property("북부 숲","서식지","북부"));
    }

    @Test @DisplayName("명시 반영 중 세계관 도메인 오류는 전체를 되돌리고 그 오류 코드로 보류를 기록한다")
    void explicitFailureRecordsAppExceptionCode() {
        var r=release();
        var held=inJob(r.job(),job->{var d=discovery(job,"에르웬");stat(job,d.getProvisionalSubjectKey(),"에르웬",35,CharacterFactOperation.ADD);
            var d2=discovery(job,"비요른");world(job,"엘프","서식지","북부");return stat(job,d2.getProvisionalSubjectKey(),"비요른",50,CharacterFactOperation.REVIEW_REQUIRED).getId();});
        completeExtraction(r);assertThat(applicationRow(r.f()).get("status")).isEqualTo("HELD_REVIEW");
        characterReview.dismissSettingCandidate(r.f().member(),r.f().work(),held);
        doThrow(new AppException(WorldSettingErrorCode.WORLD_SETTING_NOT_FOUND)).when(worldApplication).applyAutomatically(any());
        long before=reportVersion(r.f());
        var request=new org.monitoring.catchholebackend.domain.validation.dto.request.ValidationSettingsApplicationRequest(before,"explicit-failure");
        var response=applications.applySettings(r.f().member(),r.f().work(),r.f().report(),request);
        assertThat(response.status()).isEqualTo(ValidationApplicationStatus.HELD_REVIEW);assertThat(response.attempt()).isEqualTo(2);
        assertThat(response.trigger()).isEqualTo(ValidationReviewTrigger.EXPLICIT);assertThat(response.lastErrorCode()).isEqualTo("WORLD_SETTING_NOT_FOUND");
        assertThat(response.holdReasons()).containsExactly(ValidationApplicationHoldReason.episode(ValidationApplicationHoldCode.UNKNOWN_ERROR,"WORLD_SETTING_NOT_FOUND"));
        assertThat(response.reportVersion()).as("only the hold record advanced the report").isEqualTo(before+1);
        assertNothingApplied(r);
        assertThat(applications.applySettings(r.f().member(),r.f().work(),r.f().report(),request)).as("same key replays the held receipt").isEqualTo(response);
    }

    @Test @DisplayName("기존 자동 반영 규칙이 계획한 후보를 보류하면 이미 쓴 다른 도메인까지 되돌리고 CANDIDATE_CHANGED로 보류한다")
    void contributorHoldIsNotAbsorbedAsPartialSuccess() {
        var r=release();var ids=applicable(r);
        // 세계관 contributor가 아무것도 반영하지 않은 상황(검증 실패 흡수와 같은 결과)을 만든다.
        doNothing().when(worldApplication).applyAutomatically(any());
        completeExtraction(r);
        assertNothingApplied(r);
        var reasons=reviews.getValidationReview(r.f().member(),r.f().work(),r.f().report()).application().holdReasons();
        assertThat(reasons).containsExactly(new ValidationApplicationHoldReason(ValidationApplicationHoldCode.CANDIDATE_CHANGED,ValidationApplicationCandidateType.WORLD_SETTING,ids.world(),"NOT_APPLIED"));
        assertThat(applicationRow(r.f()).get("last_error_code")).isNull();
        assertThat(List.of(jobState(r.job()))).containsExactly("SUCCEEDED/SEALED");
    }
}
