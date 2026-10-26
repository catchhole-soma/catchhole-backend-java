package org.monitoring.catchholebackend.domain.analysis.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("분석 카드 요약 OpenAPI 계약")
class AnalysisBatchOpenApiIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    @DisplayName("nullable 상태와 실패 코드는 enum 또는 null의 합집합으로 문서화한다")
    void documentsNullableSummaryEnumsAsUnions() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.AnalysisBatchSummaryResponse.properties.episodeStatus.anyOf[0].enum[0]").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.components.schemas.AnalysisBatchSummaryResponse.properties.episodeStatus.anyOf[1].type").value("null"))
                .andExpect(jsonPath("$.components.schemas.AnalysisBatchSettingBookResponse.properties.failureCode.anyOf[0].enum[0]").value("AI_TOKEN_QUOTA_EXHAUSTED"))
                .andExpect(jsonPath("$.components.schemas.AnalysisBatchSettingBookResponse.properties.failureCode.anyOf[1].type").value("null"));
    }
}
