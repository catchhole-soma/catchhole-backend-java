package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateConfirmRequest;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.service.SettingCandidateService;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactConfirmApplicationMode;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationSettingsLock;
import org.monitoring.catchholebackend.domain.validation.type.ValidationExecutionStatus;
import org.monitoring.catchholebackend.domain.validation.type.ValidationSettingsLockReason;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.*;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingCandidateService;
import org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingService;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** #381: 확정 전 검수가 접수부터 검토 종료까지 현재 확정 설정을 기준으로 삼는 동안 사람의 확정 설정 쓰기를 막는다. 수치는 합성 fixture 값이다(대기 잠금 10분). */
@SpringBootTest(properties="spring.config.import=") @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@DisplayName("#381 검수 접수부터 검토 종료까지 확정 설정 잠금")
class ValidationSettingsLockIntegrationTest extends ValidationReviewFixture {
    static final String LOCKED="VALIDATION_SETTINGS_LOCKED";
    @Autowired ValidationSettingsLock locks;
    @Autowired AnalysisRunStateService runStates;
    @Autowired WorldSettingService worldSettings;
    @Autowired SettingCandidateService characterCandidates;
    @Autowired WorldSettingCandidateService worldCandidates;
    WorkCharacter archived;

    @BeforeEach void archivedCharacter() {
        archived=WorkCharacter.create(work,"보관 인물",null,30,1,null,null,null,null,null,null);archived.archive();em.persist(archived);em.flush();
    }

    @Test @DisplayName("현재·유효 report의 새 QUEUED·lease가 남은 RUNNING·검토 종료 전 COMPLETED만 잠그고 대기 시간 초과·lease 만료·PARTIAL·FAILED·CANCELED·진단·무효·퇴역·다른 작품은 잠그지 않는다")
    void onlyFreshQueuedLiveRunningAndUnreviewedCompletedJobsLock() throws Exception {
        assertThat(locks.find(work.getId())).as("검수 접수 전").isEmpty();
        var report=start();UUID reportId=UUID.fromString(report.path("reportId").asText());UUID jobId=UUID.fromString(report.path("validationJobId").asText());
        assertThat(locks.find(work.getId())).hasValueSatisfying(lock->{
            assertThat(lock.reportId()).isEqualTo(reportId);assertThat(lock.validationJobId()).isEqualTo(jobId);assertThat(lock.episodeNo()).isEqualTo(101);
            assertThat(lock.executionStatus()).isEqualTo(ValidationExecutionStatus.QUEUED);assertThat(lock.leaseExpiresAt()).isNull();
        });
        var other=Work.create(member,"다른 합성 작품",WorkGenre.FANTASY,null);em.persist(other);em.flush();
        assertThat(locks.find(other.getId())).as("다른 작품").isEmpty();

        jobUpdate("updated_at",LocalDateTime.now().minusMinutes(11),jobId);
        assertThat(locks.find(work.getId())).as("대기 잠금 시간을 넘긴 QUEUED").isEmpty();
        jobUpdate("updated_at",LocalDateTime.now().minusMinutes(9),jobId);
        assertThat(locks.find(work.getId())).as("대기 잠금 시간 안의 QUEUED").isPresent();

        claim();
        assertThat(locks.find(work.getId())).hasValueSatisfying(lock->{
            assertThat(lock.executionStatus()).isEqualTo(ValidationExecutionStatus.RUNNING);assertThat(lock.leaseExpiresAt()).isAfter(Instant.now());
        });
        jobUpdate("lease_expires_at",Instant.now().minusSeconds(1),jobId);
        assertThat(locks.find(work.getId())).as("lease가 만료된 RUNNING(죽은 Worker)").isEmpty();
        jobUpdate("lease_expires_at",Instant.now().plusSeconds(60),jobId);
        assertThat(locks.find(work.getId())).as("lease가 남은 RUNNING").isPresent();

        for(var terminal:List.of(ValidationExecutionStatus.PARTIAL,ValidationExecutionStatus.FAILED,ValidationExecutionStatus.CANCELED,ValidationExecutionStatus.NOT_STARTED)) {
            jobUpdate("status",terminal.name(),jobId);
            assertThat(locks.find(work.getId())).as(terminal.name()).isEmpty();
        }
        jobUpdate("status","COMPLETED",jobId);
        assertThat(locks.find(work.getId())).as("검토 종료 receipt 없는 COMPLETED").hasValueSatisfying(lock->{
            assertThat(lock.executionStatus()).isEqualTo(ValidationExecutionStatus.COMPLETED);assertThat(lock.reason()).isEqualTo(ValidationSettingsLockReason.REVIEW);
        });
        reportUpdate("mode='DIAGNOSTIC_ONLY'",reportId);
        assertThat(locks.find(work.getId())).as("진단 전용 COMPLETED(검토 종료가 없는 설계)").isEmpty();
        jobUpdate("status","RUNNING",jobId);
        assertThat(locks.find(work.getId())).as("진단 전용 RUNNING").hasValueSatisfying(lock->assertThat(lock.reason()).isEqualTo(ValidationSettingsLockReason.EXECUTION));
        reportUpdate("mode='BEFORE_CONFIRMATION'",reportId);

        String status=(String)em.createNativeQuery("select status from validation_reports where id=?").setParameter(1,reportId).getSingleResult();
        reportUpdate("status='INVALIDATED'",reportId);
        assertThat(locks.find(work.getId())).as("무효 report").isEmpty();
        reportUpdate("status='"+status+"'",reportId);
        assertThat(locks.find(work.getId())).isPresent();
        reportUpdate("is_current=false",reportId);
        assertThat(locks.find(work.getId())).as("퇴역 report").isEmpty();
    }

    @Test @DisplayName("대기·실행 중 캐릭터 수정은 409와 잠근 검수 context를 돌려주고 아무것도 쓰거나 무효화하지 않는다")
    void lockedCharacterEditReturnsConflictWithContext() throws Exception {
        var report=start();String reportId=report.path("reportId").asText();String jobId=report.path("validationJobId").asText();
        editCharacter(21).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value(LOCKED))
                .andExpect(jsonPath("$.message").value("오류 리포트 검수가 끝나지 않아 설정을 바꿀 수 없어요. 오류 리포트에서 수정하거나 검토를 마친 뒤 저장해 주세요."))
                .andExpect(jsonPath("$.error.context.lockReason").value("EXECUTION"))
                .andExpect(jsonPath("$.error.context.reportId").value(reportId)).andExpect(jsonPath("$.error.context.validationJobId").value(jobId))
                .andExpect(jsonPath("$.error.context.episodeNo").value(101)).andExpect(jsonPath("$.error.context.executionStatus").value("QUEUED"))
                .andExpect(jsonPath("$.error.context.leaseExpiresAt").doesNotExist());
        claim();
        editCharacter(21).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value(LOCKED))
                .andExpect(jsonPath("$.error.context.executionStatus").value("RUNNING")).andExpect(jsonPath("$.error.context.leaseExpiresAt").isString());
        em.clear();
        assertThat(em.find(WorkCharacter.class,character.getId()).getCurrentAge()).isEqualTo(20);
        assertThat(get(UUID.fromString(reportId)).path("reviewStage").asText()).isEqualTo("VALIDATING");
    }

    @Test @DisplayName("보관·복구, 세계관 생성·대상·설정 추가·설정 수정, 캐릭터·세계관 후보 확정은 실행 중 409이고 report는 유효하다")
    void everyConfirmedSettingWriterIsLocked() throws Exception {
        var report=start();claim();
        authorized(delete("/api/v1/works/"+work.getId()+"/characters/"+character.getId())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value(LOCKED));
        authorized(patch("/api/v1/works/"+work.getId()+"/characters/"+archived.getId()+"/restore")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value(LOCKED));
        // 설정 수정·대상 수정은 엔티티를 먼저 바꾼 뒤 검사한다. 실제 요청은 되돌려지므로 공유 테스트 트랜잭션에서는 매번 영속성 컨텍스트를 비운다.
        locked(()->worldSettings.createWorldSetting(member.getId(),work.getId(),new WorldSettingCreateRequest(WorldSettingCategory.RACE,"새 종족","수명","길다")));
        locked(()->worldSettings.addWorldSettingProperty(member.getId(),work.getId(),world.getId(),new WorldSettingPropertyCreateRequest("언어","고대어",worldVersion())));
        locked(()->worldSettings.updateWorldSettingProperty(member.getId(),work.getId(),world.getId(),new WorldSettingPropertyUpdateRequest("서식지","서식지","남부",worldVersion())));
        locked(()->worldSettings.updateWorldSettingIdentity(member.getId(),work.getId(),world.getId(),new WorldSettingIdentityUpdateRequest(WorldSettingCategory.RACE,"바뀐 종족",worldVersion())));
        var source=sourceJob();
        UUID characterCandidate=characterCandidate(source);UUID worldCandidate=worldCandidate(source);
        locked(()->characterCandidates.confirmSettingCandidate(member.getId(),work.getId(),characterCandidate,new SettingCandidateConfirmRequest(CharacterFactConfirmApplicationMode.APPLY_PROPOSAL,null)));
        locked(()->worldCandidates.confirmCandidate(member.getId(),work.getId(),worldCandidate,
                new WorldSettingCandidateConfirmRequest(WorldSettingOperation.ADD,WorldSettingCategory.RACE,"엘프","서식지","북부",false,null)));
        em.clear();
        assertThat(em.find(WorkCharacter.class,character.getId()).getStatus().name()).isEqualTo("ACTIVE");
        assertThat(em.find(WorkCharacter.class,archived.getId()).getStatus().name()).isEqualTo("ARCHIVED");
        assertThat(get(UUID.fromString(report.path("reportId").asText())).path("reviewStage").asText()).as("확정 설정 변경 event가 나가지 않았다").isEqualTo("VALIDATING");
    }

    @Test @DisplayName("확정 설정을 바꾸지 않는 검토 대기 후보 제외와 관계·종료 경로의 공통 검사는 실행 중에도 허용한다")
    void writesThatLeaveValidationInputsUnchangedAreNotLocked() throws Exception {
        var report=start();claim();
        var source=sourceJob();UUID characterCandidate=characterCandidate(source);UUID worldCandidate=worldCandidate(source);
        assertThat(characterCandidates.dismissSettingCandidate(member.getId(),work.getId(),characterCandidate).reviewStatus().name()).isEqualTo("DISMISSED");
        assertThat(worldCandidates.dismissCandidate(member.getId(),work.getId(),worldCandidate,new WorldSettingCandidateDismissRequest(null)).reviewStatus().name()).isEqualTo("DISMISSED");
        // 관계도 정정·삭제(RelationshipGraphServiceImpl)와 #369 종료(AnalysisStartCheckServiceImpl), 결정 초안 저장은 이 공통 검사만 쓴다.
        assertThatCode(()->runStates.assertSettingMutationAllowed(work.getId())).doesNotThrowAnyException();
        locked(()->runStates.assertConfirmedSettingMutationAllowed(work.getId()));
        assertThat(get(UUID.fromString(report.path("reportId").asText())).path("reviewStage").asText()).isEqualTo("VALIDATING");
    }

    @ParameterizedTest(name="{0}")
    @ValueSource(strings={"QUEUED_TIMEOUT","LEASE_EXPIRED","WORKER_FAILED","PARTIAL","CANCELED"})
    @DisplayName("대기 시간 초과·lease 만료·실패·부분 완료·취소 뒤에는 같은 수정이 성공하고 report는 기존 규칙대로 무효화된다")
    void releasedLockAllowsEditAndInvalidatesReport(String release) throws Exception {
        var report=start();UUID reportId=UUID.fromString(report.path("reportId").asText());UUID jobId=UUID.fromString(report.path("validationJobId").asText());
        editCharacter(21).andExpect(status().isConflict());em.clear();
        switch(release) {
            case "QUEUED_TIMEOUT"->jobUpdate("updated_at",LocalDateTime.now().minusMinutes(11),jobId);
            case "LEASE_EXPIRED"->{claim();jobUpdate("lease_expires_at",Instant.now().minusSeconds(1),jobId);}
            case "WORKER_FAILED"->{var job=claim();
                assertThat(internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","stop","failureCode","MODEL_CALL_FAILED"),200).path("status").asText()).isEqualTo("FAILED");}
            default->{claim();jobUpdate("status",release,jobId);}
        }
        assertThat(locks.find(work.getId())).isEmpty();
        editCharacter(21).andExpect(status().isOk());
        em.clear();
        assertThat(em.find(WorkCharacter.class,character.getId()).getCurrentAge()).isEqualTo(21);
        var staging=get(reportId);
        assertThat(staging.path("reviewStage").asText()).isEqualTo("INVALIDATED");assertThat(staging.path("status").asText()).isEqualTo("INVALIDATED");
    }

    @Test @DisplayName("실행이 끝나도 검토 종료 전에는 설정 화면 수정이 lockReason REVIEW로 409이고 검토를 마치면 잠금이 끝난다")
    void completedRunKeepsSettingsLockedUntilReviewCompletion() throws Exception {
        var report=reviewPending();String reportId=report.path("reportId").asText();
        editCharacter(21).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value(LOCKED))
                .andExpect(jsonPath("$.error.context.reportId").value(reportId)).andExpect(jsonPath("$.error.context.executionStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.error.context.lockReason").value("REVIEW")).andExpect(jsonPath("$.error.context.leaseExpiresAt").doesNotExist());
        em.clear();
        locked(()->worldSettings.updateWorldSettingProperty(member.getId(),work.getId(),world.getId(),new WorldSettingPropertyUpdateRequest("서식지","서식지","남부",worldVersion())));
        var source=sourceJob();UUID worldCandidate=worldCandidate(source);
        locked(()->worldCandidates.confirmCandidate(member.getId(),work.getId(),worldCandidate,
                new WorldSettingCandidateConfirmRequest(WorldSettingOperation.ADD,WorldSettingCategory.RACE,"엘프","서식지","북부",false,null)));
        assertThat(get(UUID.fromString(reportId)).path("reviewStage").asText()).isEqualTo("AWAITING_REVIEW");

        UUID release=completeReview(UUID.fromString(reportId));
        assertThat(locks.find(work.getId())).as("검토 종료 receipt가 생기면 잠금이 끝난다").isEmpty();
        // 검토 종료가 만든 release 추출(순차 자동 분석)이 대기하는 동안은 기존 순차 분석 대기 검사가 직접 수정을 막는다(#381과 무관한 기존 동작).
        editCharacter(21).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ANALYSIS_REVIEW_WAIT_REQUIRED"));em.clear();
        em.createNativeQuery("update analysis_jobs set status='CANCELED' where id=?1").setParameter(1,release).executeUpdate();em.clear();
        editCharacter(21).andExpect(status().isOk());
    }

    @ParameterizedTest(name="{0}")
    @ValueSource(strings={"characters","worldSettings"})
    @DisplayName("검토 단계에서는 그 리포트의 정정만 통과하고 리포트는 기존 규칙대로 무효화되어 잠금이 끝난다")
    void sameReportCorrectionPassesTheReviewLock(String domain) throws Exception {
        var report=reviewPending();UUID reportId=UUID.fromString(report.path("reportId").asText());
        var receipt=postOk(correctionPath(report),correctionRequest(reportId,domain,"review-fix"));
        assertThat(receipt.path("correctionId").isTextual()).isTrue();
        em.clear();
        if(domain.equals("characters"))assertThat(em.find(WorkCharacter.class,character.getId()).getCurrentAge()).isEqualTo(21);
        else assertThat(em.find(WorldSetting.class,world.getId()).getPropertyValue("서식지")).isEqualTo("남부");
        assertThat(get(reportId).path("reviewStage").asText()).isEqualTo("INVALIDATED");
        assertThat(locks.find(work.getId())).isEmpty();
    }

    @Test @DisplayName("그 리포트의 실행 중 정정과 다른 리포트가 잠근 동안의 정정은 409다")
    void correctionStaysLockedWhileRunningOrLockedByAnotherReport() throws Exception {
        var report=start();UUID reportId=UUID.fromString(report.path("reportId").asText());claim();
        correctionConflict(reportId,"worldSettings","running-fix").andExpect(jsonPath("$.error.code").value(LOCKED))
                .andExpect(jsonPath("$.error.context.reportId").value(reportId.toString())).andExpect(jsonPath("$.error.context.lockReason").value("EXECUTION"));
        em.clear();
        assertThat(em.find(WorldSetting.class,world.getId()).getPropertyValue("서식지")).isEqualTo("북부");
        assertThat(get(reportId).path("reviewStage").asText()).isEqualTo("VALIDATING");
    }

    @Test @DisplayName("검토 단계의 정정도 다른 리포트의 검수가 대기 중이면 그 리포트 context로 409다")
    void reviewCorrectionIsBlockedByAnotherReportsLock() throws Exception {
        var report=reviewPending();UUID reportId=UUID.fromString(report.path("reportId").asText());
        UUID other=queueValidationOnPriorEpisode();
        correctionConflict(reportId,"characters","other-lock").andExpect(jsonPath("$.error.code").value(LOCKED))
                .andExpect(jsonPath("$.error.context.reportId").value(other.toString())).andExpect(jsonPath("$.error.context.lockReason").value("EXECUTION"));
        em.clear();
        assertThat(em.find(WorkCharacter.class,character.getId()).getCurrentAge()).isEqualTo(20);
        assertThat(get(reportId).path("reviewStage").asText()).isEqualTo("AWAITING_REVIEW");
    }

    /** 검토 단계의 리포트(오탈자 제안 두 건, 자동 검토 종료 없음)와 그 잠금을 확인한다. */
    JsonNode reviewPending() throws Exception {
        var report=typoReviewPending();UUID reportId=UUID.fromString(report.path("reportId").asText());
        assertThat(locks.find(work.getId())).hasValueSatisfying(lock->{assertThat(lock.reportId()).isEqualTo(reportId);assertThat(lock.reason()).isEqualTo(ValidationSettingsLockReason.REVIEW);});
        return report;
    }
    UUID completeReview(UUID reportId) throws Exception {return completeReviewKeepingTypos(reportId);}
    Map<String,Object> correctionRequest(UUID reportId,String domain,String key) throws Exception {
        var request=correction(get(reportId),domain,key);
        if(domain.equals("characters"))request.put("character",Map.of("targetId",character.getId(),"expectedSnapshotVersion",em.find(WorkCharacter.class,character.getId()).getSnapshotVersion(),
                "factType","AGE","factKey","age","edit",characterEdit(21,1)));
        else {long version=worldVersion();
            request.put("worldSetting",Map.of("targetId",world.getId(),"expectedVersion",version,"edit",Map.of("currentSettingName","서식지","settingName","서식지","settingValue","남부","version",version)));}
        return request;
    }
    ResultActions correctionConflict(UUID reportId,String domain,String key) throws Exception {
        return authorized(post(base()+"/"+reportId+"/corrections").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(correctionRequest(reportId,domain,key))))
                .andExpect(status().isConflict());
    }
    /** 앞 회차(100)에 막 접수된 QUEUED 검수 하나. 같은 작품의 다른 리포트 잠금만 만든다. */
    UUID queueValidationOnPriorEpisode() {
        var report=org.monitoring.catchholebackend.domain.validation.entity.ValidationReport.create(em.find(Work.class,work.getId()),
                em.find(org.monitoring.catchholebackend.domain.episode.entity.Episode.class,prior.getId()),em.find(UploadBatch.class,batchId),"0".repeat(64));
        em.persist(report);
        em.persist(org.monitoring.catchholebackend.domain.validation.entity.ValidationJob.queue(report,"1".repeat(64),"2".repeat(64),JsonNodeFactory.instance.objectNode()));
        em.flush();return report.getId();
    }

    ResultActions editCharacter(int age) throws Exception {
        return authorized(patch("/api/v1/works/"+work.getId()+"/characters/"+character.getId()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(characterEdit(age,1))));
    }
    ResultActions authorized(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization","Bearer "+token));
    }
    void locked(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AppException.class,e->assertThat(e.getResultCode().getCode()).isEqualTo(LOCKED));
        em.clear();
    }
    long worldVersion() {return em.find(WorldSetting.class,world.getId()).getVersion();}
    void jobUpdate(String column,Object value,UUID jobId) {
        em.flush();em.createNativeQuery("update validation_jobs set "+column+"=?1 where id=?2").setParameter(1,value).setParameter(2,jobId).executeUpdate();em.clear();
    }
    void reportUpdate(String set,UUID reportId) {
        em.flush();em.createNativeQuery("update validation_reports set "+set+" where id=?1").setParameter(1,reportId).executeUpdate();em.clear();
    }
    /** 앞 회차(100)의 수동 추출 Job. 검토 대기 후보의 원본이며 검수 회차(101)와 겹치지 않는다. */
    AnalysisJob sourceJob() {
        var job=AnalysisJob.create(em.find(Work.class,work.getId()),em.find(UploadBatch.class,batchId),em.find(org.monitoring.catchholebackend.domain.episode.entity.Episode.class,prior.getId()),AnalysisJobType.SETTING_EXTRACTION);
        em.persist(job);em.flush();return job;
    }
    UUID characterCandidate(AnalysisJob job) {
        var candidate=SettingCandidate.create(job.getWork(),job.getEpisode(),null,job,SettingEntityType.CHARACTER,"합성 인물","stats.mental","35",SettingValueType.NUMBER,
                JsonNodeFactory.instance.objectNode().put("value",35),JsonNodeFactory.instance.arrayNode(),BigDecimal.ONE,null);
        em.persist(candidate);em.flush();return candidate.getId();
    }
    UUID worldCandidate(AnalysisJob job) {
        var candidate=WorldSettingCandidate.create(job.getWork(),job.getEpisode(),job,WorldSettingCategory.RACE,"엘프","서식지","북부",JsonNodeFactory.instance.arrayNode(),BigDecimal.ONE,null);
        em.persist(candidate);em.flush();return candidate.getId();
    }
}
