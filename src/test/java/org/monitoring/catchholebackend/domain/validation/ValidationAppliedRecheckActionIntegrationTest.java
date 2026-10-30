package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUploadRequest;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeUploadType;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * #314 B2 ⑤: 회차 전체 확정(APPLIED) 현재 report의 allowedActions RECHECK는 재검수 요청과 같은 회차 조건(ValidationRecheckAdmission)일 때만 나온다.
 * 실제 release Job을 커밋 경계로 claim·완료하므로 S3 테스트처럼 메서드마다 새 Context/H2를 쓴다.
 */
@SpringBootTest(properties="spring.config.import=") @ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@DisplayName("#314 B2 APPLIED report의 재검수 안내와 실제 재검수 규칙")
class ValidationAppliedRecheckActionIntegrationTest extends ValidationSettingsApplicationFixture {
    @BeforeEach void chunks() {jdbc.execute("create table if not exists episode_chunks (id uuid primary key, episode_id uuid)");}

    Fixture applied() {
        var r=release();applicable(r);completeExtraction(r);var f=r.f();
        assertThat(applicationRow(f).get("status")).isEqualTo("APPLIED");return f;
    }
    List<ValidationReviewAction> actions(Fixture f) {return reviews.getValidationReview(f.member(),f.work(),f.report()).allowedActions();}
    static String code(Throwable e) {return ((AppException)e).getResultCode().getCode();}

    @Test @DisplayName("최신 회차의 APPLIED report는 RECHECK를 안내하고 같은 원고 재검수가 진단 전용으로 접수된다")
    void appliedLatestEpisodeOffersRecheckAndRecheckSucceeds() {
        var f=applied();
        var review=reviews.getValidationReview(f.member(),f.work(),f.report());
        assertThat(review.reviewStage()).isEqualTo(ValidationReviewStage.APPLIED);assertThat(review.allowedActions()).containsExactly(ValidationReviewAction.RECHECK);
        var next=recheck(f,"diagnostic-from-action");
        assertThat(admissions.get(f.member(),f.work(),next.report()).mode()).isEqualTo(ValidationReportMode.DIAGNOSTIC_ONLY);
        assertThat(actions(f)).as("the retired report offers nothing").isEmpty();
        assertThat(analysisJobs(f)).as("diagnostic recheck creates no extraction").isEqualTo(1);
    }

    @Test @DisplayName("다음 회차가 등록되면 APPLIED report에 RECHECK가 없고 재검수도 같은 사유로 거절된다")
    void laterEpisodeHidesRecheckAndRecheckIsRejected() {
        var f=applied();
        episodes.uploadEpisodes(f.member(),f.work(),new EpisodeUploadRequest(EpisodeUploadType.SINGLE_EPISODE,102,"다음",null),
                List.of(new MockMultipartFile("episodeFiles","102.txt","text/plain","다음 합성 원고".getBytes(StandardCharsets.UTF_8))));
        assertThat(actions(f)).doesNotContain(ValidationReviewAction.RECHECK);
        assertThatThrownBy(()->recheck(f,"not-latest")).extracting(ValidationAppliedRecheckActionIntegrationTest::code).isEqualTo("VALIDATION_TARGET_INVALID");
    }

    @Test @DisplayName("회차에 진행 중 추출·비교 Job이 있으면 RECHECK가 없고 재검수도 거절된다")
    void runningAnalysisHidesRecheckAndRecheckIsRejected() {
        var f=applied();
        // 같은 회차의 진행 중 실행을 합성한다(공개 생성은 현재 검수 report 때문에 막힌다). 원래 상태로 되돌리면 다시 안내한다.
        var job=jdbc.queryForObject("select id from analysis_jobs where work_id=?",java.util.UUID.class,f.work());
        jdbc.update("update analysis_jobs set status='RUNNING' where id=?",job);
        assertThat(actions(f)).doesNotContain(ValidationReviewAction.RECHECK);
        assertThatThrownBy(()->recheck(f,"running")).extracting(ValidationAppliedRecheckActionIntegrationTest::code).isEqualTo("VALIDATION_TARGET_INVALID");
        jdbc.update("update analysis_jobs set status='SUCCEEDED' where id=?",job);
        assertThat(actions(f)).containsExactly(ValidationReviewAction.RECHECK);
    }
}
