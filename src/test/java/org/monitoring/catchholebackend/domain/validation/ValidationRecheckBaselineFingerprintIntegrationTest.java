package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.transaction.annotation.Transactional;

/** #314 B2 ⑥: 재검수 expectedBaselineHash는 단건 조회의 baselineFingerprint이며 누락·불일치 409는 context.reason으로 원고 충돌과 구분된다. */
@SpringBootTest(properties="spring.config.import=") @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@DisplayName("#314 B2 재검수 기준 식별값 계약")
class ValidationRecheckBaselineFingerprintIntegrationTest extends ValidationRecheckFixture {
    JsonNode rejected(JsonNode report,Map<String,Object> input) throws Exception {
        var result=mvc.perform(post(base()+recheckPath(report)).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VALIDATION_SOURCE_VERSION_CONFLICT")).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("error").path("context");
    }

    @Test @DisplayName("무효화 뒤 baselineHash는 null이고 baselineFingerprint를 그대로 보낸 재검수만 접수된다")
    void fingerprintFromStagingIsTheExpectedBaselineHash() throws Exception {
        var first=start();replace("바뀐 합성 원고");var staging=get(UUID.fromString(first.path("reportId").asText()));
        assertThat(staging.path("baselineHash").isNull()).as("invalidated report drops the readable hash").isTrue();
        assertThat(staging.path("baselineFingerprint").asText()).isEqualTo(first.path("baselineHash").asText());
        var missing=recheck(first,"missing");missing.remove("expectedBaselineHash");
        assertThat(rejected(first,missing).path("reason").asText()).isEqualTo("BASELINE_FINGERPRINT_REQUIRED");
        var wrong=recheck(first,"wrong");wrong.put("expectedBaselineHash","f".repeat(64));
        assertThat(rejected(first,wrong).path("reason").asText()).isEqualTo("BASELINE_FINGERPRINT_MISMATCH");
        var manuscript=recheck(first,"manuscript");manuscript.put("expectedContentRevision",target.getContentRevision()+1);
        assertThat(rejected(first,manuscript).isEmpty()).as("manuscript conflicts keep an empty context").isTrue();
        var accepted=recheck(first,"fingerprint");accepted.put("expectedBaselineHash",staging.path("baselineFingerprint").asText());
        var next=postOk(recheckPath(first),accepted);
        assertThat(next.path("previousReportId").asText()).isEqualTo(first.path("reportId").asText());noExtraction();
    }
}
