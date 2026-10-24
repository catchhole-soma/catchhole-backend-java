package org.monitoring.catchholebackend.domain.worldsetting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.support.AnalysisProgressApiFixture;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("세계관 비교 진행 집계 HTTP 계약")
class WorldSettingCandidateProgressControllerIntegrationTest extends AnalysisProgressApiFixture {
    @Test
    @DisplayName("비어 있는 유효 배치는 세 집계만 0으로 반환한다")
    void returnsOnlyThreeZeroCounts() throws Exception {
        var result = mvc.perform(get(comparisonUrl()).header("Authorization", authorization).queryParam("batchId", batch.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pendingComparisonCount").value(0))
                .andExpect(jsonPath("$.data.processingComparisonCount").value(0)).andExpect(jsonPath("$.data.tokenInterruptedComparisonCount").value(0)).andReturn();
        assertThat(json.readTree(result.getResponse().getContentAsString()).path("data").propertyNames())
                .containsExactlyInAnyOrder("pendingComparisonCount", "processingComparisonCount", "tokenInterruptedComparisonCount");
    }

    @Test
    @DisplayName("필수 배치와 인증, 작품 및 배치 소유권을 검증한다")
    void validatesReadScope() throws Exception {
        mvc.perform(get(comparisonUrl()).queryParam("batchId", batch.getId().toString())).andExpect(status().isUnauthorized());
        mvc.perform(get(comparisonUrl()).header("Authorization", authorization)).andExpect(status().isBadRequest());
        mvc.perform(get(comparisonUrl()).header("Authorization", authorization).queryParam("batchId", "invalid")).andExpect(status().isBadRequest());
        for (UUID id : java.util.List.of(UUID.randomUUID(), otherBatch.getId())) {
            mvc.perform(get(comparisonUrl()).header("Authorization", authorization).queryParam("batchId", id.toString()))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("WORLD_SETTING_CANDIDATE_BATCH_NOT_FOUND"));
        }
        mvc.perform(get("/api/v1/works/" + otherWork.getId() + "/world-setting-candidates/progress")
                .header("Authorization", authorization).queryParam("batchId", otherBatch.getId().toString())).andExpect(status().isNotFound());
    }
}
