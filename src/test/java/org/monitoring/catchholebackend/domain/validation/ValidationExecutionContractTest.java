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
                for(var unavailable:example.path("unreadableSourceRefs"))if(unavailable.asText().equals(request.sourceRef()))return json.createObjectNode().put("status","SOURCE_UNREADABLE");
                String text=request.sourceRef().equals("T1")?target:example.path("sourceTexts").path(request.sourceRef()).asText();return json.createObjectNode().put("status","READY").put("text",text).put("totalCodePoints",text.codePointCount(0,text.length()));});
            var validator=new ValidationPublicationValidator(service,decision,new ValidationClueCollector(),mapping);
            if(example.has("expectedError"))assertThatThrownBy(()->prepareGrid(validator,context,ValidationBranch.SETTING,input,example.path("prepareBookRefs"))).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
            else {assertThat(journal.canonicalJson(prepareGrid(validator,context,ValidationBranch.SETTING,input,example.path("prepareBookRefs")).grid())).as(example.path("name").asText()).isEqualTo(journal.canonicalJson(example.path("expectedSetting")));assertThat(journal.canonicalJson(prepareGrid(validator,context,ValidationBranch.TYPO,input,example.path("prepareBookRefs")).grid())).isEqualTo(journal.canonicalJson(example.path("expectedTypo")));}
        }
    }
    ValidationPublicationValidator.Prepared prepareGrid(ValidationPublicationValidator validator,ValidationPublicationValidator.Context context,ValidationBranch branch,JsonNode input,JsonNode preparedBooks) {
        var prepared=validator.prepare(context,branch,null,null);
        if(branch==ValidationBranch.SETTING)for(var source:input.path("sources"))if(source.path("sourceKind").asText().equals("SETTING_BOOK")&&(!preparedBooks.isArray()||java.util.stream.StreamSupport.stream(preparedBooks.spliterator(),false).anyMatch(ref->ref.asText().equals(source.path("sourceRef").asText()))))prepared=validator.prepare(context,branch,prepared.grid(),source.path("sourceRef").asText());
        return prepared;
    }
    @Test @DisplayName("서버 projection은 페이지와 무관한 S 참조와 원문 ref를 보존한다") void canonicalProjectionHasStableSubjectAndSourceRefs() throws Exception {
        var f=fixture().path("projectionVector");var sources=new LinkedHashMap<String,JsonNode>();f.path("sources").properties().forEach(e->sources.put(e.getKey(),e.getValue()));
        var mapper=new ValidationDataMapper(journal);assertThat(json.<JsonNode>valueToTree(mapper.toValidationSettings(f.path("entries"),sources))).isEqualTo(f.path("expected"));
        assertThat(mapper.toValidationSourceCatalogue(sources)).isEqualTo(f.path("sourceCatalogue"));
    }
    @Test @DisplayName("정상 세계관의 text와 quote 속성은 원문 인용 필드로 오인하지 않는다")
    void canonicalWorldTextAndQuotePropertiesAreNotSourceContexts() throws Exception {
        var f=fixture();var entries=f.path("projectionVector").path("entries").deepCopy();
        var properties=(com.fasterxml.jackson.databind.node.ObjectNode)entries.path(1).path("state").path("propertiesJson");
        properties.put("text","합성 세계 설정 값").put("quote","합성 이름 속성");
        var mapper=new ValidationDataMapper(journal);var settings=mapper.toValidationSettings(entries,Map.of());
        var payload=(com.fasterxml.jackson.databind.node.ObjectNode)f.path("requests").path("settingUnit").path("payload").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)payload.path("evidence_bundle")).set("settings",json.valueToTree(settings.values()));
        var validator=payloadValidator();var prepared=payloadContext(entries);var citations=payloadCitations();
        assertThatCode(()->validator.payload(prepared,payload,citations)).doesNotThrowAnyException();
        ((com.fasterxml.jackson.databind.node.ObjectNode)payload.path("evidence_bundle").path("settings").path(1).path("state").path("propertiesJson")).put("text","검증되지 않은 복사본문");
        assertThatThrownBy(()->validator.payload(prepared,payload,citations)).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
    }
    @Test @DisplayName("ref만 담은 settings 행은 받고 대상이 다르거나 추가 키에 복사본문을 숨기면 거절한다")
    void refOnlySettingRowsPassAndHiddenCopiesFail() throws Exception {
        var f=fixture();var payload=(com.fasterxml.jackson.databind.node.ObjectNode)f.path("requests").path("settingUnit").path("payload").deepCopy();
        var row=((com.fasterxml.jackson.databind.node.ObjectNode)payload.path("evidence_bundle")).putArray("settings").addObject().put("entry_ref","B2").put("subject_ref","S2");
        var validator=payloadValidator();var prepared=payloadContext(f.path("projectionVector").path("entries"));var citations=payloadCitations();
        assertThatCode(()->validator.payload(prepared,payload,citations)).doesNotThrowAnyException();
        row.put("subject_ref","S1");
        assertThatThrownBy(()->validator.payload(prepared,payload,citations)).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
        row.put("subject_ref","S2").put("quote","검증되지 않은 복사본문");
        assertThatThrownBy(()->validator.payload(prepared,payload,citations)).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
    }
    @Test @DisplayName("정의된 인용 경로 밖 복사와 필수 context 인용 누락은 계속 거절한다")
    void undeclaredCopiesAndMissingContextCitationsRemainInvalid() throws Exception {
        var payload=(com.fasterxml.jackson.databind.node.ObjectNode)fixture().path("requests").path("settingUnit").path("payload").deepCopy();var prepared=payloadContext(json.createArrayNode());var validator=payloadValidator();var citations=payloadCitations();
        payload.putObject("untrusted").put("text","😀가😀가");
        assertThatThrownBy(()->validator.payload(prepared,payload,citations)).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
        payload.remove("untrusted");
        assertThatThrownBy(()->validator.payload(prepared,payload,List.of(citations.getFirst()))).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
    }
    ValidationPublicationValidator payloadValidator() {
        var data=mock(ValidationDataService.class);when(data.readValidationData(anyLong(),any(),any(),any(),any(),any())).thenAnswer(call->{var request=call.getArgument(3,org.monitoring.catchholebackend.domain.validation.dto.request.ValidationDataRequest.class);String text="😀가😀가";int start=request.start()==null?0:request.start(),end=request.end()==null?4:request.end();return json.createObjectNode().put("status","READY").put("start",start).put("end",end).put("text",text.substring(text.offsetByCodePoints(0,start),text.offsetByCodePoints(0,end)));});
        var mapper=new ValidationDataMapper(journal);return new ValidationPublicationValidator(data,new ValidationDecisionValidator(mapper,journal),new ValidationClueCollector(),mapper);
    }
    ValidationPublicationValidator.Prepared payloadContext(JsonNode entries) throws Exception {
        var citations=payloadCitations();String hash=citations.getFirst().sourceHash();var c=new ValidationPublicationValidator.Context(UUID.randomUUID(),UUID.randomUUID(),new Scope(1,UUID.randomUUID(),1,hash),1L,UUID.randomUUID(),hash,hash,json.createObjectNode(),entries,Map.of());
        return new ValidationPublicationValidator.Prepared(c,ValidationBranch.SETTING,json.createObjectNode(),new HashMap<>(Map.of("T1",json.createObjectNode().put("contentHash",hash))),entries.size());
    }
    List<Citation> payloadCitations() throws Exception {return json.convertValue(fixture().path("requests").path("settingUnit").path("citations"),new com.fasterxml.jackson.core.type.TypeReference<>(){});}
}
