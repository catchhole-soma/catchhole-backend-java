package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** GH312 S3 POST /settings-application과 /review 확장의 HTTP 계약. 보류는 정상 결과(200)이고 진행 중 추출은 409다. */
@SpringBootTest(properties="spring.config.import=") @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@DisplayName("GH312 S3 회차 전체 확정 API 계약")
class ValidationSettingsApplicationApiIntegrationTest extends ValidationReviewFixture {
    JsonNode applySettings(UUID report,Map<String,Object> body,int status) throws Exception {
        var result=mvc.perform(post(base()+"/"+report+"/settings-application").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().is(status)).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path(status==200?"data":"error");
    }
    long rows(UUID report) {return count("validation_settings_applications",report);}

    @Test @DisplayName("release 추출이 대기·진행 중이면 409 NOT_READY이고 실패한 추출은 200 보류 receipt와 같은 키 재전송을 반환한다")
    void notReadyThenHeldReceiptOverHttp() throws Exception {
        var report=zeroFindingCompletion();var release=releaseOnly();long version=review(report).path("reportVersion").asLong();
        var summary=review(report);
        assertThat(summary.path("application").isNull()).isTrue();assertThat(summary.path("extractionState").asText()).isEqualTo("RUNNING");
        assertThat(texts(summary.path("allowedActions"))).containsExactly("APPLY_SETTINGS");
        assertThat(code(applySettings(report,Map.of("expectedReportVersion",version,"idempotencyKey","http-queued"),409))).isEqualTo("VALIDATION_APPLICATION_NOT_READY");
        applySettings(report,Map.of("expectedReportVersion",version),400);assertThat(rows(report)).isZero();
        // 추출이 실패로 끝나면 반영할 수 없다는 보류 receipt가 정상 결과다.
        em.flush();em.createNativeQuery("update analysis_jobs set status='FAILED' where id=:j").setParameter("j",release).executeUpdate();em.clear();
        var body=Map.<String,Object>of("expectedReportVersion",version,"idempotencyKey","http-failed");
        var held=applySettings(report,body,200);
        assertThat(held.path("status").asText()).isEqualTo("HELD_REVIEW");assertThat(held.path("trigger").asText()).isEqualTo("EXPLICIT");assertThat(held.path("attempt").asLong()).isEqualTo(1);
        assertThat(held.path("holdReasons").path(0).path("code").asText()).isEqualTo("EXTRACTION_NOT_FINISHED");assertThat(held.path("holdReasons").path(0).path("candidateType").asText()).isEqualTo("EPISODE");
        assertThat(held.path("appliedAt").isNull()).isTrue();assertThat(held.path("extractionJobId").asText()).isEqualTo(release.toString());assertThat(held.path("extractionJobGeneration").asLong()).isEqualTo(1);
        assertThat(held.path("reportVersion").asLong()).isEqualTo(version+1);
        assertThat(applySettings(report,body,200)).as("same key replays the receipt").isEqualTo(held);assertThat(rows(report)).isEqualTo(1);
        var after=review(report);
        assertThat(after.path("application").path("status").asText()).isEqualTo("HELD_REVIEW");assertThat(after.path("application").path("holdReasons").path(0).path("code").asText()).isEqualTo("EXTRACTION_NOT_FINISHED");
        assertThat(after.path("extractionState").asText()).isEqualTo("FAILED");assertThat(texts(after.path("allowedActions"))).containsExactly("APPLY_SETTINGS");
        assertThat(get(report).path("candidateStatus").asText()).isEqualTo("PENDING");
    }

    @Test @DisplayName("검토 종료 전 report는 409 UNAVAILABLE, 다른 작품의 report는 404다")
    void unavailableBeforeReviewCompletion() throws Exception {
        var current=reportId(completedWithFindings(1));
        long version=review(current).path("reportVersion").asLong();
        assertThat(code(applySettings(current,Map.of("expectedReportVersion",version,"idempotencyKey","early"),409))).isEqualTo("VALIDATION_APPLICATION_UNAVAILABLE");
        assertThat(code(applySettings(UUID.randomUUID(),Map.of("expectedReportVersion",version,"idempotencyKey","missing"),404))).isEqualTo("VALIDATION_NOT_FOUND");
        assertThat(rows(current)).isZero();
    }
}
