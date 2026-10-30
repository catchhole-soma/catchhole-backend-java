package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.validation.dto.request.*;
import org.monitoring.catchholebackend.domain.validation.dto.response.*;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationReviewStages;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.global.common.response.PageResponse;

@DisplayName("GH312 작가 검토 공개 API v1 계약 fixture")
class ValidationReviewContractTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    final AnalysisStateJournal journal=new AnalysisStateJournal();
    JsonNode fixture() throws Exception {return json.readTree(Path.of("docs/contracts/validation-review-v1.json").toFile());}
    void roundTrips(String name,JsonNode input,JavaType type) throws Exception {
        assertThat(input.isObject()).as(name).isTrue();
        Object value=json.readValue(json.treeAsTokens(input),type);
        assertThat(journal.canonicalJson(json.valueToTree(value))).as(name).isEqualTo(journal.canonicalJson(input));
    }

    @Test @DisplayName("요청·응답 DTO의 필드와 fixture가 정확히 왕복한다")
    void requestAndResponseDtosRoundTrip() throws Exception {
        var f=fixture();assertThat(f.path("contractVersion").asInt()).isEqualTo(1);var types=json.getTypeFactory();
        Map<String,Class<?>> requests=Map.of("judgment",ValidationJudgmentRequest.class,"reviewCompletion",ValidationReviewCompletionRequest.class,"settingsApplication",ValidationSettingsApplicationRequest.class);
        for(var e:requests.entrySet())roundTrips(e.getKey(),f.path("requests").path(e.getKey()),types.constructType(e.getValue()));
        Map<String,JavaType> responses=Map.of(
                "findingSummary",types.constructType(ValidationFindingSummaryResponse.class),
                "findingDetail",types.constructType(ValidationFindingDetailResponse.class),
                "reviewSummary",types.constructType(ValidationReviewSummaryResponse.class),
                "reviewCompletion",types.constructType(ValidationReviewCompletionResponse.class),
                "settingsApplication",types.constructType(ValidationSettingsApplicationResponse.class),
                "historyPage",types.constructType(new TypeReference<PageResponse<ValidationReportHistoryResponse>>(){}),
                "batchValidationReport",types.constructType(ValidationRequestSummaryResponse.class));
        for(var e:responses.entrySet())roundTrips(e.getKey(),f.path("responses").path(e.getKey()),e.getValue());
        assertThat(f.path("routes").size()).isEqualTo(7);
    }

    @Test @DisplayName("과거 이력 응답은 요약만 담고 인용·근거·판정 내용이 없다")
    void historyIsSummaryOnly() throws Exception {
        var page=fixture().path("responses").path("historyPage");
        for(var row:page.path("content")) {
            assertThat(row.toString()).doesNotContain("quote","evidence","payload","judgmentHistory");
            assertThat(row.path("counts").size()).isEqualTo(2);
        }
        assertThat(page.path("content").path(0).path("revision").asLong()).as("newest first").isGreaterThan(page.path("content").path(1).path("revision").asLong());
    }

    @Test @DisplayName("검수 단계 enum과 예시 입력이 서버 계산식과 같다")
    void reviewStageCasesMatchResolver() throws Exception {
        var f=fixture();var names=new ArrayList<String>();f.path("reviewStages").forEach(n->names.add(n.asText()));
        assertThat(names).containsExactlyElementsOf(Arrays.stream(ValidationReviewStage.values()).map(Enum::name).toList());
        for(var c:f.path("reviewStageCases")) {
            var in=c.path("input");
            var input=new ValidationReviewStages.Input(in.path("current").asBoolean(),ValidationReportStatus.valueOf(in.path("status").asText()),ValidationPreparationStatus.valueOf(in.path("baselineStatus").asText()),
                    ValidationExecutionStatus.valueOf(in.path("execution").asText()),in.path("reviewCompleted").asBoolean(),ValidationExtractionState.valueOf(in.path("extraction").asText()),
                    in.path("application").isNull()?null:ValidationApplicationStatus.valueOf(in.path("application").asText()));
            assertThat(ValidationReviewStages.resolve(input).name()).as(c.path("name").asText()).isEqualTo(c.path("expected").asText());
        }
    }
}
