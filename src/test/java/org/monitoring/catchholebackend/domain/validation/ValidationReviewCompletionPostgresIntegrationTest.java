package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationReviewCompletionRequest;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationReviewCompletionResponse;
import org.monitoring.catchholebackend.domain.validation.type.ValidationReviewCompletionMode;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

/** Destructive fixture cleanup is guarded to a new disposable loopback DB, never .env/shared DB. */
@SpringBootTest(properties={"spring.config.import=","spring.docker.compose.enabled=false"}) @ActiveProfiles("test") @DirtiesContext
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@EnabledIfEnvironmentVariable(named="GH310_EXECUTION_POSTGRES_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gh310_execution_test")
@DisplayName("격리 PostgreSQL의 검토 종료 receipt·동시 요청·자동 종료 보류")
class ValidationReviewCompletionPostgresIntegrationTest extends ValidationReviewCompletionServiceFixture {
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->System.getenv("GH310_EXECUTION_POSTGRES_JDBC_URL"));p.add("spring.datasource.username",()->"gh310");p.add("spring.datasource.password",()->"gh310-disposable-only");
        p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");p.add("spring.jpa.hibernate.ddl-auto",()->"validate");p.add("spring.flyway.enabled",()->"true");
    }
    @BeforeEach void clean() {jdbc.execute("TRUNCATE TABLE works,members CASCADE");}
    static void await(CountDownLatch latch) {try {if(!latch.await(15,TimeUnit.SECONDS))throw new AssertionError("synthetic latch timed out");}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AssertionError(ex);}}
    /** 두 요청을 동시에 출발시켜 응답 또는 예외를 모은다. */
    List<Object> race(Fixture f,String firstKey,String secondKey) throws Exception {
        long version=reportVersion(f);var go=new CountDownLatch(1);var ready=new CountDownLatch(2);List<Object> outcomes=new ArrayList<>();
        try(var pool=Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures=new ArrayList<>();
            for(String key:List.of(firstKey,secondKey))futures.add(pool.submit(()->{ready.countDown();await(go);
                try {return completions.completeValidationReview(f.member(),f.work(),f.report(),new ValidationReviewCompletionRequest(version,key));}catch(AppException e) {return e;}}));
            await(ready);go.countDown();for(var future:futures)outcomes.add(future.get(30,TimeUnit.SECONDS));
        }
        return outcomes;
    }

    @Test @DisplayName("실제 Flyway V97·V98·JPA validate에서 자동 종료 receipt와 release Job이 FK·제약과 함께 저장된다")
    void flywayV97AndJpaValidateStoreAutomaticReceipt() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version in ('97','98') and success",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select indexdef from pg_indexes where indexname='uk_validation_review_completions_key'",String.class)).contains("UNIQUE").contains("WHERE (idempotency_key IS NOT NULL)");
        var f=fixture();var run=runToCompletion(f,false);
        assertThat(run.done().get("extractionJobId")).isNotNull();assertThat(receipts(f)).isEqualTo(1);assertThat(analysisJobs(f)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select trigger from validation_review_completions where work_id=?",String.class,f.work())).isEqualTo("AUTOMATIC");
        assertThat(jdbc.queryForObject("select count(*) from validation_review_completions c join analysis_jobs j on j.id=c.extraction_job_id where c.work_id=? and j.job_type='SETTING_EXTRACTION' and j.analysis_mode='ORDERED_PROVISIONAL' and j.review_mode='AUTOMATIC'",Integer.class,f.work())).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("update validation_review_completions set trigger='AUTOMATIC', idempotency_key='x' where work_id=?",f.work())).as("AUTOMATIC receipts carry no request key").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(run.sameReceipt(run.replay(results))).isTrue();assertThat(analysisJobs(f)).isEqualTo(1);
    }

    @Test @DisplayName("다른 키의 동시 명시 검토 완료는 receipt·Job 하나와 이미 종료 409 하나를 만든다")
    void concurrentExplicitCompletionsWithDifferentKeysCreateOneReceipt() throws Exception {
        var f=fixture();runToCompletion(f,true);keepAllTypos(f);
        assertThat(reviews.getValidationReview(f.member(),f.work(),f.report()).completionMode()).isEqualTo(ValidationReviewCompletionMode.EXPLICIT_REQUIRED);
        var outcomes=race(f,"tab-a","tab-b");
        assertThat(outcomes).filteredOn(ValidationReviewCompletionResponse.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(AppException.class::isInstance).extracting(o->((AppException)o).getResultCode().getCode()).containsExactly("VALIDATION_REVIEW_ALREADY_COMPLETED");
        assertThat(receipts(f)).isEqualTo(1);assertThat(analysisJobs(f)).isEqualTo(1);
    }

    @Test @DisplayName("같은 키의 동시 명시 검토 완료는 같은 receipt를 반환하고 Job은 하나다")
    void concurrentExplicitCompletionsWithSameKeyReturnSameReceipt() throws Exception {
        var f=fixture();runToCompletion(f,true);keepAllTypos(f);
        var outcomes=race(f,"same-tab","same-tab");
        assertThat(outcomes).allMatch(ValidationReviewCompletionResponse.class::isInstance);
        assertThat(outcomes.stream().map(o->((ValidationReviewCompletionResponse)o).completionId()).distinct()).hasSize(1);
        assertThat(receipts(f)).isEqualTo(1);assertThat(analysisJobs(f)).isEqualTo(1);
    }

    @Test @DisplayName("자동 종료 보류는 첫 트랜잭션을 모두 되돌리고 명시 재시도로 receipt를 만든다")
    void automaticReleaseHoldRollsBackOnPostgres() {
        var f=fixture();exhaustTokens(f);var run=runToCompletion(f,false);
        assertThat(run.done().get("reviewCompletionId")).isNull();assertThat(receipts(f)).isZero();assertThat(analysisJobs(f)).isZero();
        assertThat(jdbc.queryForObject("select first_analysis_started_at from members where id=?",java.sql.Timestamp.class,f.member())).isNull();
        assertThat(reviews.getValidationReview(f.member(),f.work(),f.report()).completionMode()).isEqualTo(ValidationReviewCompletionMode.AUTOMATIC_ELIGIBLE);
        assertThat(jdbc.queryForObject("select release_hold_code from validation_reports where id=? and release_hold_at is not null",String.class,f.report())).isEqualTo("AI_TOKEN_QUOTA_EXHAUSTED");
        refillTokens(f);completions.completeValidationReview(f.member(),f.work(),f.report(),new ValidationReviewCompletionRequest(reportVersion(f),"after-quota"));
        assertThat(receipts(f)).isEqualTo(1);assertThat(analysisJobs(f)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from validation_reports where id=? and release_hold_code is null and release_hold_at is null",Integer.class,f.report())).isEqualTo(1);
    }
}
