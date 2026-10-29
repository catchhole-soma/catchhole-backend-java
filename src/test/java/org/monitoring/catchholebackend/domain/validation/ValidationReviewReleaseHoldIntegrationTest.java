package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationReviewCompletionRequest;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** 테스트 트랜잭션 없이 커밋 경계를 확인한다. 클래스별 H2 DB이며 작품 ID로 행을 센다. */
@SpringBootTest(properties="spring.config.import=") @ActiveProfiles("test")
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@DisplayName("GH312 S2 자동 검토 종료 보류와 명시 재시도")
class ValidationReviewReleaseHoldIntegrationTest extends ValidationReviewCompletionServiceFixture {
    @BeforeEach void chunks() {jdbc.execute("create table if not exists episode_chunks (id uuid primary key, episode_id uuid)");}

    @Test @DisplayName("자동 종료의 추출 생성 조건이 막히면 검사 결과만 저장하고 receipt·Job은 남기지 않는다")
    void automaticReleaseHeldByTokenQuotaKeepsResultAndExplicitRetryReleases() {
        var f=fixture();exhaustTokens(f);var run=runToCompletion(f,false);
        assertThat(run.done().get("reviewCompletionId")).isNull();assertThat(run.done().get("extractionJobId")).isNull();
        assertThat(analysisJobs(f)).isZero();assertThat(receipts(f)).isZero();
        assertThat(jdbc.queryForObject("select first_analysis_started_at from members where id = ?",java.sql.Timestamp.class,f.member())).as("first transaction rolled back entirely").isNull();
        var held=reviews.getValidationReview(f.member(),f.work(),f.report());
        assertThat(held.executionStatus()).isEqualTo(ValidationExecutionStatus.COMPLETED);assertThat(held.completionMode()).isEqualTo(ValidationReviewCompletionMode.AUTOMATIC_ELIGIBLE);
        assertThat(held.canCompleteReview()).isTrue();assertThat(held.allowedActions()).containsExactly(ValidationReviewAction.COMPLETE_REVIEW);assertThat(held.completion()).isNull();
        assertThat(run.sameReceipt(run.replay(results))).as("worker replay returns the stored receipt").isTrue();assertThat(analysisJobs(f)).isZero();
        long version=held.reportVersion();var request=new ValidationReviewCompletionRequest(version,"after-quota");
        assertThatThrownBy(()->completions.completeValidationReview(f.member(),f.work(),f.report(),request)).isInstanceOf(AppException.class)
                .extracting(e->((AppException)e).getResultCode().getCode()).isEqualTo("AI_TOKEN_QUOTA_EXHAUSTED");
        assertThat(analysisJobs(f)).isZero();assertThat(receipts(f)).isZero();assertThat(reportVersion(f)).isEqualTo(version);
        refillTokens(f);var receipt=completions.completeValidationReview(f.member(),f.work(),f.report(),request);
        assertThat(receipt.trigger()).isEqualTo(ValidationReviewTrigger.EXPLICIT);assertThat(receipt.reportVersion()).isEqualTo(version);
        assertThat(analysisJobs(f)).isEqualTo(1);assertThat(receipts(f)).isEqualTo(1);
        var done=reviews.getValidationReview(f.member(),f.work(),f.report());assertThat(done.completionMode()).isEqualTo(ValidationReviewCompletionMode.COMPLETED);
        assertThat(done.completion().extractionJobId()).isEqualTo(receipt.extractionJobId());
    }

    @Test @DisplayName("커밋된 정상 0건 자동 종료는 receipt와 release Job을 함께 남긴다")
    void committedAutomaticReleaseCreatesReceiptAndJob() {
        var f=fixture();var run=runToCompletion(f,false);
        assertThat(run.done().get("extractionJobId")).isNotNull();assertThat(analysisJobs(f)).isEqualTo(1);assertThat(receipts(f)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select trigger from validation_review_completions where work_id = ?",String.class,f.work())).isEqualTo("AUTOMATIC");
        assertThat(run.sameReceipt(run.replay(results))).isTrue();assertThat(analysisJobs(f)).isEqualTo(1);
    }
}
