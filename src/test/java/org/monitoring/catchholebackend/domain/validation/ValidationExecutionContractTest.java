package org.monitoring.catchholebackend.domain.validation;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.validation.dto.ValidationExecutionContract.*;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationDataMapper;
import org.monitoring.catchholebackend.domain.validation.processor.*;
import org.monitoring.catchholebackend.domain.validation.service.ValidationDataService;
import org.monitoring.catchholebackend.domain.validation.type.*;

@DisplayName("Java/Python 검수 실행 v1 공통 wire와 code point grid")
class ValidationExecutionContractTest {
    final ObjectMapper json=new ObjectMapper();final AnalysisStateJournal journal=new AnalysisStateJournal();
    JsonNode fixture() throws Exception {return json.readTree(Path.of("docs/contracts/validation-execution-v1.json").toFile()).path("execution");}
    @Test @DisplayName("요청 DTO의 필드와 fixture가 정확히 왕복한다") void exactRequestDtosRoundTrip() throws Exception {
        var f=fixture();assertThat(f.path("contractVersion").asInt()).isEqualTo(1);
        Map<String,Class<?>> requests=Map.ofEntries(Map.entry("claim",Claim.class),Map.entry("heartbeat",Lease.class),Map.entry("plan",Publish.class),Map.entry("mergedPlan",Publish.class),Map.entry("settingUnit",Unit.class),Map.entry("typoUnit",Unit.class),Map.entry("revisions",Revise.class),Map.entry("checkpoint",Checkpoint.class),Map.entry("complete",Complete.class),Map.entry("retry",Retry.class),Map.entry("reserve",Reserve.class),Map.entry("settle",Usage.class),Map.entry("unknown",Usage.class),Map.entry("release",Usage.class));
        for(var entry:requests.entrySet()) {var input=f.path("requests").path(entry.getKey());assertThat(input.isObject()).as(entry.getKey()).isTrue();Object command=json.treeToValue(input,entry.getValue());assertThat(journal.canonicalJson(json.valueToTree(command))).as(entry.getKey()).isEqualTo(journal.canonicalJson(input));}
    }
    @Test @DisplayName("서버 hash와 Python bundle hash의 계산 규칙을 구분한다") void canonicalDigestsHaveKnownVectors() throws Exception {
        var f=fixture();for(var v:f.path("serverDigestVectors")) {assertThat(journal.canonicalJson(v.path("input"))).isEqualTo(v.path("canonicalJson").asText());assertThat(journal.hash(v.path("input"))).isEqualTo(v.path("sha256").asText());}
        var worker=f.path("workerBundleDigestVector");assertThat(journal.hash(worker.path("input"))).isNotEqualTo(worker.path("sha256").asText());
        assertThat(worker.path("canonicalJson").asText()).contains(": ",", ");
    }
    @Test @DisplayName("원문 길이와 설정집 수에서 계산한 필수 grid가 공통 fixture와 같다") void serverDerivesNonBmpEmptyAndMultipleBookGrid() throws Exception {
        var f=fixture();var mapping=new ValidationDataMapper(journal);var decision=new ValidationDecisionValidator(mapping,journal);
        for(var example:f.path("gridCases")) {
            var service=mock(ValidationDataService.class);String hash="a".repeat(64),target=example.path("target").asText();UUID job=UUID.randomUUID(),report=UUID.randomUUID(),lease=UUID.randomUUID(),work=UUID.randomUUID();
            var context=new ValidationPublicationValidator.Context(job,lease,new Scope(1,report,1,hash),1L,work,hash,hash,example.path("configuration"),example.has("baselineEntries")?example.path("baselineEntries"):json.createArrayNode(),Map.of());
            var input=json.createObjectNode().put("status","READY").put("entryCount",example.path("entryCount").asInt());input.putObject("target").put("contentS3Version","v1");input.set("sources",example.path("sources"));
            when(service.readValidationData(anyLong(),any(),any(),any(),any(),any())).thenAnswer(call->{var request=call.getArgument(3,org.monitoring.catchholebackend.domain.validation.dto.request.ValidationDataRequest.class);if(request.operation().equals("INPUT"))return input;
                String text=request.sourceRef().equals("T1")?target:example.path("sourceTexts").path(request.sourceRef()).asText();return json.createObjectNode().put("status","READY").put("text",text).put("totalCodePoints",text.codePointCount(0,text.length()));});
            var validator=new ValidationPublicationValidator(service,decision,new ValidationClueCollector(),mapping);
            if(example.has("expectedError"))assertThatThrownBy(()->validator.prepare(context,ValidationBranch.SETTING)).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
            else {assertThat(journal.canonicalJson(validator.prepare(context,ValidationBranch.SETTING).grid())).as(example.path("name").asText()).isEqualTo(journal.canonicalJson(example.path("expectedSetting")));assertThat(journal.canonicalJson(validator.prepare(context,ValidationBranch.TYPO).grid())).isEqualTo(journal.canonicalJson(example.path("expectedTypo")));}
        }
    }
    @Test @DisplayName("서버 projection은 페이지와 무관한 S 참조와 원문 ref를 보존한다") void canonicalProjectionHasStableSubjectAndSourceRefs() throws Exception {
        var f=fixture().path("projectionVector");var sources=new LinkedHashMap<String,JsonNode>();f.path("sources").properties().forEach(e->sources.put(e.getKey(),e.getValue()));
        var mapper=new ValidationDataMapper(journal);assertThat(json.<JsonNode>valueToTree(mapper.toValidationSettings(f.path("entries"),sources))).isEqualTo(f.path("expected"));
        assertThat(mapper.toValidationSourceCatalogue(sources)).isEqualTo(f.path("sourceCatalogue"));
    }
}
