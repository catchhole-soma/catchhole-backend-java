package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** GH312 작가 검토 공개 API의 공식 OpenAPI. 출력은 build/ 아래에만 남긴다. */
@SpringBootTest(properties = "spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("GH312 작가 검토 API OpenAPI 계약")
class EpisodeValidationOpenApiIntegrationTest {
    static final String BASE = "/api/v1/works/{workId}/episode-validations";
    @Autowired MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();

    record Endpoint(String method, String path, String operationId, String responseSchema, List<String> errors) {}
    static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint("get", BASE + "/{reportId}/findings", "listValidationFindings", "PageResponseValidationFindingSummaryResponse", List.of("400", "404")),
            new Endpoint("get", BASE + "/{reportId}/findings/{findingId}", "getValidationFinding", "ValidationFindingDetailResponse", List.of("404")),
            new Endpoint("patch", BASE + "/{reportId}/findings/{findingId}/judgment", "saveValidationJudgment", "ValidationFindingSummaryResponse", List.of("400", "404", "409")),
            new Endpoint("get", BASE + "/{reportId}/review", "getValidationReviewSummary", "ValidationReviewSummaryResponse", List.of("404")),
            new Endpoint("post", BASE + "/{reportId}/review-completion", "completeValidationReview", "ValidationReviewCompletionResponse", List.of("400", "404", "409")),
            new Endpoint("post", BASE + "/{reportId}/settings-application", "applyValidationSettings", "ValidationSettingsApplicationResponse", List.of("400", "404", "409")),
            new Endpoint("get", BASE + "/history", "listEpisodeValidationHistory", "PageResponseValidationReportHistoryResponse", List.of("400", "404")),
            new Endpoint("get", BASE + "/{reportId}", "getEpisodeValidationStaging", "ValidationStagingResponse", List.of("404")),
            new Endpoint("get", BASE + "/current", "getCurrentEpisodeValidation", "ValidationStagingResponse", List.of("404")));

    JsonNode docs() throws Exception {
        String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Path out = Path.of("build", "openapi", "validation-review-api-docs.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, body);
        return json.readTree(body);
    }

    @Test
    @DisplayName("작가 검토 endpoint는 고유 operationId·응답 schema·대표 4xx 응답과 bearer 인증을 문서화한다")
    void documentsReviewEndpoints() throws Exception {
        var docs = docs();
        Set<String> operationIds = new HashSet<>();
        docs.path("paths").forEach(path -> path.forEach(operation -> operationIds.add(operation.path("operationId").asText())));
        for (var endpoint : ENDPOINTS) {
            var operation = docs.path("paths").path(endpoint.path()).path(endpoint.method());
            assertThat(operation.isObject()).as(endpoint.path()).isTrue();
            assertThat(operation.path("operationId").asText()).as(endpoint.path()).isEqualTo(endpoint.operationId());
            assertThat(operation.path("summary").asText()).as(endpoint.operationId()).isNotBlank();
            assertThat(operation.path("description").asText()).as(endpoint.operationId()).isNotBlank();
            assertThat(operation.toString()).as(endpoint.operationId() + " response schema").contains("#/components/schemas/CommonResponse" + endpoint.responseSchema());
            for (String code : endpoint.errors()) assertThat(operation.path("responses").has(code)).as(endpoint.operationId() + " " + code).isTrue();
            assertThat(operation.path("security").toString()).as(endpoint.operationId()).contains("bearerAuth");
        }
        assertThat(operationIds).contains(ENDPOINTS.stream().map(Endpoint::operationId).toArray(String[]::new));
        for (String body : List.of("ValidationJudgmentRequest", "ValidationReviewCompletionRequest", "ValidationSettingsApplicationRequest"))
            assertThat(docs.path("components").path("schemas").has(body)).as(body).isTrue();
    }

    @Test
    @DisplayName("검수 단계는 모든 projection에서 같은 enum이며 이력 nested schema는 다른 응답과 이름이 겹치지 않는다")
    void documentsReviewStageAndHistorySchemas() throws Exception {
        var schemas = docs().path("components").path("schemas");
        var stages = List.of("PREPARING_BASELINE", "VALIDATING", "AWAITING_REVIEW", "REVIEW_COMPLETED", "EXTRACTION_FAILED", "APPLICATION_HELD", "APPLIED", "HELD_INCOMPLETE", "INVALIDATED");
        for (String owner : List.of("ValidationStagingResponse", "ValidationReviewSummaryResponse", "ValidationReportHistoryResponse", "ValidationRequestSummaryResponse"))
            assertThat(enumValues(schemas.path(owner).path("properties").path("reviewStage"))).as(owner).containsExactlyElementsOf(stages);
        assertThat(enumValues(schemas.path("EpisodeSummaryResponse").path("properties").path("validationReviewStage"))).containsExactlyElementsOf(stages);
        var history = schemas.path("ValidationReportHistoryResponse").path("properties");
        assertThat(history.path("counts").toString()).contains("ValidationReportHistoryCounts");
        assertThat(history.path("completion").toString()).contains("ValidationReportHistoryCompletion");
        assertThat(history.path("application").toString()).contains("ValidationReportHistoryApplication");
        assertThat(history.path("releaseHold").toString()).contains("ValidationReportHistoryReleaseHold");
        for (String field : List.of("quote", "evidence", "payload", "judgments")) assertThat(history.has(field)).as(field).isFalse();
        // 검토 요약의 nested record는 다른 응답의 동명 record와 섞이지 않아야 한다.
        assertThat(schemas.path("ValidationReviewSummaryResponse").path("properties").path("counts").toString()).contains("ValidationReviewSummaryCounts");
        for (String generic : List.of("Counts", "Completion", "Application", "ReleaseHold", "Summary", "Span", "Evidence", "History", "Judgment", "PriorRevision"))
            assertThat(schemas.has(generic)).as("nested validation schema must not use the global name " + generic).isFalse();
    }

    List<String> enumValues(JsonNode property) {
        var values = new ArrayList<String>();
        var node = property.has("enum") ? property : property.path("anyOf").path(0).has("enum") ? property.path("anyOf").path(0) : property;
        node.path("enum").forEach(v -> values.add(v.asText()));
        return values;
    }
}
