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
import org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationIntakeEligibility;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationNextEpisodeAdmission;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationReviewStages;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.common.response.PageResponse;

@DisplayName("GH312 작가 검토·#314 B1 접수 자격·B2 검수 공개 계약 API v1 fixture")
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
        Map<String,JavaType> responses=new LinkedHashMap<>();
        for(String name:List.of("findingSummary","findingSummaryWorld","findingSummaryTypo"))responses.put(name,types.constructType(ValidationFindingSummaryResponse.class));
        responses.put("findingDetail",types.constructType(ValidationFindingDetailResponse.class));
        for(String name:List.of("reviewSummary","reviewSummaryApplied"))responses.put(name,types.constructType(ValidationReviewSummaryResponse.class));
        responses.put("reviewCompletion",types.constructType(ValidationReviewCompletionResponse.class));
        responses.put("settingsApplication",types.constructType(ValidationSettingsApplicationResponse.class));
        responses.put("historyPage",types.constructType(new TypeReference<PageResponse<ValidationReportHistoryResponse>>(){}));
        responses.put("batchValidationReport",types.constructType(ValidationRequestSummaryResponse.class));
        for(String name:List.of("eligibilityEligible","eligibilityNotLatestEpisode","eligibilityNextEpisodeBlocked","eligibilityWorkOnlyBlocked","eligibilityReportExists"))
            responses.put(name,types.constructType(ValidationEligibilityResponse.class));
        for(var e:responses.entrySet())roundTrips(e.getKey(),f.path("responses").path(e.getKey()),e.getValue());
        assertThat(f.path("responses").size()).as("every response example is round-tripped").isEqualTo(responses.size());
        for(String error:List.of("nextEpisodeBlocked","recheckBaselineFingerprintRequired","recheckBaselineFingerprintMismatch"))
            roundTrips("errors."+error,f.path("errors").path(error),types.constructType(new TypeReference<CommonResponse<Void>>(){}));
        assertThat(f.path("errors").size()).as("every error example is round-tripped").isEqualTo(3);
        roundTrips("correctionFromFindingBasis.request",f.path("correctionFromFindingBasis").path("request"),types.constructType(ValidationCorrectionRequest.class));
        roundTrips("correctionFromFindingBasis.recheckWithReceipt",f.path("correctionFromFindingBasis").path("recheckWithReceipt"),types.constructType(ValidationRecheckRequest.class));
        assertThat(f.path("routes").size()).isEqualTo(8);
    }

    @Test @DisplayName("접수 자격 예시는 서버 판정 문구와 같고 409 차단 context가 같은 report를 가리킨다")
    void eligibilityExamplesMatchServerWording() throws Exception {
        var f=fixture();var responses=f.path("responses");
        for(String name:List.of("eligibilityEligible","eligibilityNotLatestEpisode","eligibilityNextEpisodeBlocked","eligibilityReportExists")) {
            var example=responses.path(name);var reason=ValidationEligibilityReason.valueOf(example.path("reasonCode").asText());
            assertThat(example.path("message").asText()).as(name).isEqualTo(ValidationIntakeEligibility.message(reason));
            assertThat(example.path("eligible").asBoolean()).as(name).isEqualTo(reason==ValidationEligibilityReason.ELIGIBLE);
        }
        var workOnly=responses.path("eligibilityWorkOnlyBlocked");
        for(String field:List.of("episodeId","eligible","reasonCode","message","latestEpisode","currentReport"))assertThat(workOnly.path(field).isNull()).as(field).isTrue();
        var error=f.path("errors").path("nextEpisodeBlocked");var code=ValidationErrorCode.VALIDATION_NEXT_EPISODE_BLOCKED;
        assertThat(error.path("error").path("code").asText()).isEqualTo(code.name());assertThat(error.path("error").path("status").asInt()).isEqualTo(code.getStatus().value());
        assertThat(error.path("message").asText()).isEqualTo(code.getMessage());
        var context=error.path("error").path("context");var names=new ArrayList<String>();context.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("reportId","episodeId","episodeNo","reviewStage");
        var blocked=responses.path("eligibilityNextEpisodeBlocked").path("nextEpisodeAdmission");
        assertThat(blocked.path("blockingReportId").asText()).isEqualTo(context.path("reportId").asText());assertThat(blocked.path("blockingEpisodeNo").asInt()).isEqualTo(context.path("episodeNo").asInt());
        assertThat(blocked.path("message").asText()).isEqualTo(code.getMessage());
    }

    @Test @DisplayName("다음 회차 차단 예시가 서버 규칙(BEFORE_CONFIRMATION이고 APPLIED가 아니면 INVALIDATED도 차단, APPLIED receipt가 있으면 허용)과 같다")
    void nextEpisodeAdmissionCasesMatchRule() throws Exception {
        var cases=fixture().path("nextEpisodeAdmissionCases");var covered=EnumSet.noneOf(ValidationReviewStage.class);
        for(var c:cases) {
            var mode=ValidationReportMode.valueOf(c.path("mode").asText());var stage=ValidationReviewStage.valueOf(c.path("reviewStage").asText());
            var application=c.path("application").isMissingNode()||c.path("application").isNull()?null:ValidationApplicationStatus.valueOf(c.path("application").asText());
            assertThat(ValidationNextEpisodeAdmission.blocks(mode,stage,application)).as(mode+"/"+stage+"/"+application).isEqualTo(c.path("blocks").asBoolean());
            if(mode==ValidationReportMode.BEFORE_CONFIRMATION&&application==null)covered.add(stage);
        }
        assertThat(covered).as("every stage is listed for BEFORE_CONFIRMATION").containsExactlyInAnyOrder(ValidationReviewStage.values());
    }

    @Test @DisplayName("#314 B2: 항목 대상·설정 키 예시는 서버 enum·키 형식과 같고 오탈자는 대상이 없다")
    void findingTargetExamplesMatchServerTypes() throws Exception {
        var responses=fixture().path("responses");
        for(String name:List.of("findingSummary","findingSummaryWorld","findingDetail")) {
            var example=responses.path(name);
            assertThat(ValidationSubjectType.valueOf(example.path("subject").path("type").asText())).as(name).isNotNull();
            assertThat(example.path("settingName").asText()).as(name).isNotBlank();
        }
        var character=responses.path("findingSummary");String key=character.path("settingKey").asText();
        assertThat(org.monitoring.catchholebackend.domain.character.type.CharacterFactType.valueOf(key.substring(0,key.indexOf(':')))).isNotNull();
        var world=json.readTree(responses.path("findingSummaryWorld").path("settingKey").asText());
        assertThat(world.isArray()&&world.size()==2).as("world setting key is the stored path key").isTrue();
        assertThat(responses.path("findingSummaryWorld").path("settingName").asText()).isEqualTo(world.get(0).asText()+" / "+world.get(1).asText());
        var typo=responses.path("findingSummaryTypo");
        for(String field:List.of("subject","settingName","settingKey"))assertThat(typo.path(field).isNull()).as(field).isTrue();
        var impacts=new ArrayList<String>();for(var impact:ValidationImpact.values())impacts.add(impact.name());
        for(String name:List.of("findingSummary","findingSummaryWorld"))assertThat(impacts).contains(responses.path(name).path("summary").path("impact").asText());
        assertThat(texts(responses.path("reviewSummaryApplied").path("allowedActions"))).containsExactly("RECHECK");
        assertThat(responses.path("reviewSummaryApplied").path("reviewStage").asText()).isEqualTo("APPLIED");
    }

    @Test @DisplayName("#314 B2: 정정 요청 예시는 단건 조회 기준값과 상세 basis 값을 그대로 옮기고 재검수 409 예시는 사유로 구분된다")
    void correctionMappingCopiesBasisAndStaging() throws Exception {
        var f=fixture();var mapping=f.path("correctionFromFindingBasis");var staging=mapping.path("staging");var request=mapping.path("request");
        var basis=f.path("responses").path("findingDetail").path("basis").path(0);
        assertThat(request.path("baselineId")).isEqualTo(staging.path("baselineId"));assertThat(request.path("expectedBaselineRevision")).isEqualTo(staging.path("baselineRevision"));
        assertThat(request.path("expectedBaselineHash")).isEqualTo(staging.path("baselineHash"));assertThat(request.path("expectedReportVersion")).isEqualTo(staging.path("reportVersion"));
        assertThat(request.path("entryRef")).isEqualTo(basis.path("entryRef"));assertThat(request.path("expectedEntryHash")).isEqualTo(basis.path("entryHash"));
        var character=request.path("character");
        assertThat(character.path("targetId")).isEqualTo(basis.path("targetId"));assertThat(character.path("expectedSnapshotVersion")).isEqualTo(basis.path("currentVersion"));
        assertThat(character.path("factType")).isEqualTo(basis.path("factType"));assertThat(character.path("factKey")).isEqualTo(basis.path("factKey"));
        assertThat(basis.path("subjectType").asText()).isEqualTo("CHARACTER");
        assertThat(mapping.path("recheckWithReceipt").path("expectedBaselineHash")).as("recheck sends the staging fingerprint").isEqualTo(staging.path("baselineFingerprint"));
        var code=ValidationErrorCode.VALIDATION_SOURCE_VERSION_CONFLICT;
        for(var e:Map.of("recheckBaselineFingerprintRequired","BASELINE_FINGERPRINT_REQUIRED","recheckBaselineFingerprintMismatch","BASELINE_FINGERPRINT_MISMATCH").entrySet()) {
            var error=f.path("errors").path(e.getKey());
            assertThat(error.path("error").path("code").asText()).isEqualTo(code.name());assertThat(error.path("error").path("status").asInt()).isEqualTo(code.getStatus().value());
            assertThat(error.path("message").asText()).isEqualTo(code.getMessage());assertThat(error.path("error").path("context").path("reason").asText()).isEqualTo(e.getValue());
        }
    }

    List<String> texts(JsonNode array) {var out=new ArrayList<String>();array.forEach(n->out.add(n.asText()));return out;}

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
