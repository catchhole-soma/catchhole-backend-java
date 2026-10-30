package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationSettingsApplicationRequest;
import org.monitoring.catchholebackend.domain.validation.dto.response.*;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingCandidateServiceImpl;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Destructive fixture cleanup is guarded to a new disposable loopback DB, never .env/shared DB. */
@SpringBootTest(properties={"spring.config.import=","spring.docker.compose.enabled=false"}) @ActiveProfiles("test") @DirtiesContext
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@EnabledIfEnvironmentVariable(named="GH310_EXECUTION_POSTGRES_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gh310_execution_test")
@DisplayName("격리 PostgreSQL의 회차 전체 확정 receipt·동시 반영·전체 되돌림")
class ValidationSettingsApplicationPostgresIntegrationTest extends ValidationSettingsApplicationFixture {
    @MockitoSpyBean WorldSettingCandidateServiceImpl worldApplication;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->System.getenv("GH310_EXECUTION_POSTGRES_JDBC_URL"));p.add("spring.datasource.username",()->"gh310");p.add("spring.datasource.password",()->"gh310-disposable-only");
        p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");p.add("spring.jpa.hibernate.ddl-auto",()->"validate");p.add("spring.flyway.enabled",()->"true");
    }
    /** Worker claim은 DB 전체의 대기 Job을 고르므로 매 테스트 전에 합성 작품·회원 그래프를 모두 지운다. */
    @BeforeEach void clean() {jdbc.execute("TRUNCATE TABLE works,members CASCADE");}
    static void await(CountDownLatch latch) {try {if(!latch.await(15,TimeUnit.SECONDS))throw new AssertionError("synthetic latch timed out");}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AssertionError(ex);}}

    @Test @DisplayName("실제 Flyway V99·JPA validate에서 자동 경로의 APPLIED receipt가 FK·CHECK와 함께 저장된다")
    void flywayV99AndJpaValidateStoreAppliedReceipt() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='99' and success",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select indexdef from pg_indexes where indexname='uk_validation_settings_applications_key'",String.class)).contains("UNIQUE").contains("WHERE (idempotency_key IS NOT NULL)");
        var r=release();applicable(r);completeExtraction(r);var f=r.f();
        assertThat(applicationRow(f).get("status")).isEqualTo("APPLIED");assertThat(characters(f)).isEqualTo(1);assertThat(worldSettings(f)).isEqualTo(1);assertThat(automaticAppliedAt(r.job())).isNotNull();
        assertThat(jdbc.queryForObject("select jsonb_typeof(hold_reasons) is null from validation_settings_applications where report_id=?",Boolean.class,f.report())).isTrue();
        assertThatThrownBy(()->jdbc.update("update validation_settings_applications set applied_at=null where report_id=?",f.report())).as("APPLIED requires applied_at")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update validation_settings_applications set idempotency_key='x' where report_id=?",f.report())).as("AUTOMATIC attempts carry no request key")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test @DisplayName("보류를 해소한 뒤 다른 키의 동시 명시 반영은 APPLIED receipt 하나와 시도 하나만 만든다")
    void concurrentExplicitAppliesCreateOneAppliedReceipt() throws Exception {
        var r=release();var f=r.f();
        var held=inJob(r.job(),job->{var d=discovery(job,"에르웬");stat(job,d.getProvisionalSubjectKey(),"에르웬",35,CharacterFactOperation.ADD);world(job,"엘프","서식지","북부");
            var d2=discovery(job,"비요른");return stat(job,d2.getProvisionalSubjectKey(),"비요른",50,CharacterFactOperation.REVIEW_REQUIRED).getId();});
        completeExtraction(r);assertThat(applicationRow(f).get("status")).isEqualTo("HELD_REVIEW");
        characterReview.dismissSettingCandidate(f.member(),f.work(),held);
        long version=reportVersion(f);var go=new CountDownLatch(1);var ready=new CountDownLatch(2);List<Object> outcomes=new ArrayList<>();
        try(var pool=Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures=new ArrayList<>();
            for(String key:List.of("tab-a","tab-b"))futures.add(pool.submit(()->{ready.countDown();await(go);
                try {return applications.applySettings(f.member(),f.work(),f.report(),new ValidationSettingsApplicationRequest(version,key));}catch(AppException e) {return e;}}));
            await(ready);go.countDown();for(var future:futures)outcomes.add(future.get(30,TimeUnit.SECONDS));
        }
        assertThat(outcomes).as(String.valueOf(outcomes)).allMatch(ValidationSettingsApplicationResponse.class::isInstance);
        assertThat(outcomes.stream().map(o->(ValidationSettingsApplicationResponse)o).map(o->o.applicationId()+"/"+o.status()+"/"+o.attempt()).distinct()).hasSize(1);
        assertThat(applicationRows(f)).isEqualTo(1);assertThat(((Number)applicationRow(f).get("attempt")).longValue()).isEqualTo(2);
        assertThat(characters(f)).as("applied once").isEqualTo(2);assertThat(facts(f)).isEqualTo(1);assertThat(worldSettings(f)).isEqualTo(1);
    }

    @Test @DisplayName("PostgreSQL에서도 세계관 반영 실패는 캐릭터 쓰기까지 되돌리고 추출 완료와 보류만 남긴다")
    void worldFailureRollsBackOnPostgres() {
        var r=release();applicable(r);
        doThrow(new IllegalStateException("합성 저장 실패")).when(worldApplication).applyAutomatically(any());
        completeExtraction(r);var f=r.f();
        assertThat(characters(f)).isZero();assertThat(facts(f)).isZero();assertThat(snapshotSources(f)).isZero();assertThat(worldSettings(f)).isZero();assertThat(confirmed(f)).isZero();
        assertThat(automaticAppliedAt(r.job())).isNull();assertThat(jobState(r.job())).isEqualTo("SUCCEEDED/SEALED");
        var row=applicationRow(f);assertThat(row.get("status")).isEqualTo("HELD_REVIEW");assertThat(row.get("last_error_code")).isEqualTo("UNEXPECTED_ERROR");
        assertThat(jdbc.queryForObject("select hold_reasons->0->>'code' from validation_settings_applications where report_id=?",String.class,f.report())).isEqualTo("UNKNOWN_ERROR");
        assertThat(jdbc.queryForObject("select status from validation_reports where id=?",String.class,f.report())).isEqualTo("WAITING_FOR_ERROR_REVIEW");
    }
}
