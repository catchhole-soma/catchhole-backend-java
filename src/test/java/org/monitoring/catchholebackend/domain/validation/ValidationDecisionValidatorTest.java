package org.monitoring.catchholebackend.domain.validation;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.validation.dto.ValidationExecutionContract.Scope;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationDataMapper;
import org.monitoring.catchholebackend.domain.validation.processor.*;
import org.monitoring.catchholebackend.domain.validation.type.ValidationBranch;
import org.monitoring.catchholebackend.global.exception.AppException;

@DisplayName("현재 질문의 실제 bundle과 정상 완료 영수증 검증")
class ValidationDecisionValidatorTest {
    final ObjectMapper json=new ObjectMapper();final AnalysisStateJournal journal=new AnalysisStateJournal();final ValidationDataMapper mapper=new ValidationDataMapper(journal);final ValidationDecisionValidator validator=new ValidationDecisionValidator(mapper,journal);
    JsonNode f;ObjectNode state;ObjectNode body;ValidationCheck check;ValidationPublicationValidator.Prepared prepared;
    @BeforeEach void fixture() throws Exception {
        f=json.readTree(Path.of("docs/contracts/validation-execution-v1.json").toFile()).path("execution");state=f.path("requests").path("mergedPlan").path("payload").path("questions").path(0).path("state").deepCopy();body=f.path("requests").path("settingUnit").path("payload").deepCopy();
        check=ValidationCheck.create(mock(ValidationReport.class),"Q1",json.createArrayNode().add("PLANNER:TSEG1:local-1"),state,journal.hash(state));
        var context=new ValidationPublicationValidator.Context(UUID.randomUUID(),UUID.randomUUID(),new Scope(1,UUID.randomUUID(),1,"a".repeat(64)),1L,UUID.randomUUID(),"a".repeat(64),"b".repeat(64),json.createObjectNode(),json.createArrayNode(),Map.of());
        prepared=new ValidationPublicationValidator.Prepared(context,ValidationBranch.SETTING,json.createObjectNode(),new HashMap<>(),0);
    }
    @Test @DisplayName("정상 0건도 실제 두 비교 조회 영수증으로 완료한다") void noConflictRequiresRealCompletionReceipts() {assertThatCode(()->validator.validate(prepared,check,"NO_CONFLICT",body)).doesNotThrowAnyException();}
    @Test @DisplayName("모델 완료 사유만 있고 실제 영수증이 없으면 거절한다") void reasonsAloneCannotComplete() {((ObjectNode)body.path("evidence_bundle")).set("receipts",json.createArrayNode());invalid();}
    @Test @DisplayName("실패 영수증과 필수 읽기 누락을 거절한다") void failedOrUncoveredReceiptRejected() {
        ((ObjectNode)body.path("evidence_bundle").path("receipts").path(1)).put("status","FAILED");invalid();
        ((ObjectNode)body.path("evidence_bundle").path("receipts").path(1)).put("status","SUCCEEDED");((ObjectNode)body.path("completion").path("required_reads")).set("receipt_refs",json.createArrayNode().add("L1"));invalid();
    }
    @Test @DisplayName("다른 질문이나 revision의 bundle은 완료할 수 없다") void anotherQuestionOrRevisionRejected() {((ObjectNode)body.path("evidence_bundle")).put("check_revision",2);invalid();}
    @Test @DisplayName("새 의무가 처리되지 않았으면 정상 완료로 저장하지 않는다") void pendingObligationRejected() {
        ((ArrayNode)state.path("lookup_obligations")).add(f.path("requests").path("revisions").path("changes").path(0).path("state").path("lookup_obligations").path(0));
        check=ValidationCheck.create(mock(ValidationReport.class),"Q1",json.createArrayNode(),state,journal.hash(state));invalid();
    }
    @Test @DisplayName("grid v4 계획 초안에는 조회 계획(lookup_intents)이 없고 있으면 거절하며 의무는 기본 빈 목록이다") void draftsCarryNoLookupIntentsAndObligationsDefaultToEmpty() {
        var withIntents=state.deepCopy();((ObjectNode)withIntents.path("draft")).set("lookup_intents",json.createArrayNode());
        assertThatThrownBy(()->validator.state(withIntents,Set.of())).isInstanceOf(AppException.class);
        var withoutObligations=state.deepCopy();withoutObligations.remove("lookup_obligations");
        assertThat(validator.state(withoutObligations,Set.of()).path("lookup_obligations")).isEqualTo(json.createArrayNode());
    }
    @Test @DisplayName("원문 키워드·직접·인접 읽기 의도는 grid v4에서 거절한다") void rawTextReadIntentsAreRejected() {
        for(String operation:List.of("KEYWORD","DIRECT","NEIGHBORS"))
            assertThatThrownBy(()->validator.validateIntent(json.createObjectNode().put("ref","X").put("kind","COMPARISON_CONTEXT").put("operation",operation).put("purpose","합성").set("evidence_refs",json.createArrayNode()))).as(operation).isInstanceOf(AppException.class);
        for(String operation:List.of("BASELINE","TARGET_CONTEXT","VECTOR"))
            assertThatCode(()->validator.validateIntent(json.createObjectNode().put("ref","X").put("kind","COMPARISON_CONTEXT").put("operation",operation).put("purpose","합성").set("evidence_refs",json.createArrayNode()))).as(operation).doesNotThrowAnyException();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"CONFLICT,DIRECT_CONTRADICTION","SUSPECTED_CONFLICT,CHANGE_NOT_FOUND"})
    @DisplayName("현재 회차를 E 참조로 포장해도 과거 설정 충돌의 근거가 되지 않는다")
    void targetOnlyEvidenceCannotAuthorizeConflict(String outcome,String basis) {
        conflictBundle("T1",outcome,basis);
        assertThatThrownBy(()->validator.validate(prepared,check,outcome,body)).isInstanceOf(AppException.class);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"CONFLICT,DIRECT_CONTRADICTION","SUSPECTED_CONFLICT,CHANGE_NOT_FOUND"})
    @DisplayName("검증된 고정 과거 출처의 E 참조는 충돌 비교 근거로 유지한다")
    void frozenPriorEvidenceAllowsConflict(String outcome,String basis) {
        conflictBundle("R1",outcome,basis);
        assertThatCode(()->validator.validate(prepared,check,outcome,body)).doesNotThrowAnyException();
    }
    @Test @DisplayName("충돌 의심은 변화·예외 VECTOR 검색 영수증이 있어야 하고 다른 조회 영수증으로 대신할 수 없다")
    void suspectedConflictNeedsAVectorChangeSearch() {
        conflictBundle("R1","SUSPECTED_CONFLICT","CHANGE_NOT_FOUND");
        ((ObjectNode)body.path("evidence_bundle").path("receipts").path(2).path("intent")).put("kind","COMPARISON_CONTEXT");
        assertThatThrownBy(()->validator.validate(prepared,check,"SUSPECTED_CONFLICT",body)).isInstanceOf(AppException.class);
        ((ObjectNode)body.path("evidence_bundle").path("receipts").path(2).path("intent")).put("kind","CHANGES_EXCEPTIONS").put("operation","KEYWORD");
        assertThatThrownBy(()->validator.validate(prepared,check,"SUSPECTED_CONFLICT",body)).isInstanceOf(AppException.class);
    }
    void conflictBundle(String source,String outcome,String basis) {
        var refs=Map.<String,JsonNode>of("T1",json.createObjectNode().put("sourceKind","EPISODE").put("episodeNo",101),"R1",json.createObjectNode().put("sourceKind","EPISODE").put("episodeNo",100));
        var c=prepared.context();prepared=new ValidationPublicationValidator.Prepared(new ValidationPublicationValidator.Context(c.jobId(),c.lease(),c.scope(),c.member(),c.work(),c.baselineHash(),c.targetHash(),c.configuration(),c.entries(),refs),prepared.branch(),prepared.grid(),prepared.sources(),0);
        var bundle=(ObjectNode)body.path("evidence_bundle");bundle.set("source_catalogue",mapper.toValidationSourceCatalogue(refs));
        ((ArrayNode)bundle.path("evidence")).addObject().put("ref","E1").put("source_ref",source).put("source_kind","EPISODE").put("start",0).put("end",2).put("quote","😀가");
        var receipt=((ArrayNode)bundle.path("receipts")).addObject().put("ref","L3").put("status","SUCCEEDED").put("limited",false);
        receipt.set("intent",json.createObjectNode().put("ref","CHANGES-1").put("kind","CHANGES_EXCEPTIONS").put("operation","VECTOR").put("purpose","합성 변경 조회").put("query","합성 변경").set("evidence_refs",json.createArrayNode().add("E1")));
        receipt.set("evidence_refs",json.createArrayNode().add("E1"));
        for(var assessment:body.path("completion"))((ObjectNode)assessment).set("receipt_refs",json.createArrayNode().add("L1").add("L2").add("L3"));
        body.put("outcome",outcome).put("basis",basis).put("impact","CAUTION").put("impact_reason","합성 비교 차이");body.set("evidence_refs",json.createArrayNode().add("T1").add("E1"));
    }
    @Test @DisplayName("판정 작성 제목 벡터: 없거나 null이면 통과, 글은 공백 아닌 80 code point 이하, 문제없음은 제목을 둘 수 없다(공통 계약 headline)")
    void headlineVectorsFollowTheSharedContract() throws Exception {
        var flow=f.path("judgeFlow").path("headline");
        assertThat(flow.path("maxCodePoints").asInt()).isEqualTo(ValidationDecisionValidator.MAX_HEADLINE_CODE_POINTS);
        assertThat(flow.path("vectors").size()).isGreaterThanOrEqualTo(10);
        for(var v:flow.path("vectors")) {
            fixture();String outcome=v.path("outcome").asText(),name=v.path("name").asText();
            switch(outcome) {
                case "CONFLICT" -> conflictBundle("R1","CONFLICT","DIRECT_CONTRADICTION");
                case "SUSPECTED_CONFLICT" -> conflictBundle("R1","SUSPECTED_CONFLICT","CHANGE_NOT_FOUND");
                case "NEEDS_CONTEXT" -> body.put("outcome","NEEDS_CONTEXT").put("basis","AMBIGUOUS_MEANING").put("impact","REFERENCE").put("impact_reason","합성 확인 필요");
                default -> assertThat(outcome).isEqualTo("NO_CONFLICT");
            }
            if(!v.path("omit").asBoolean())body.set("headline",v.path("headline"));else body.remove("headline");
            if(v.path("valid").asBoolean())assertThatCode(()->validator.validate(prepared,check,outcome,body)).as(name).doesNotThrowAnyException();
            else assertThatThrownBy(()->validator.validate(prepared,check,outcome,body)).as(name).isInstanceOf(AppException.class);
        }
    }
    @Test @DisplayName("bundle settings는 질문 대상 항목의 entry_ref·subject_ref만으로 완료한다") void refsOnlySettingsAccepted() {
        subjectS1(row("B1","S1"),row("B3","S1"));assertThatCode(()->validator.validate(prepared,check,"NO_CONFLICT",body)).doesNotThrowAnyException();
    }
    @Test @DisplayName("추가 키는 판정에 쓰지 않아 이전 Worker의 전체 행과 내용이 다른 행도 통과한다") void extraSettingKeysIgnored() {
        var full=mapper.toValidationSettings(f.path("projectionVector").path("entries"),Map.of());
        subjectS1(full.get("B1"),((ObjectNode)full.get("B3").deepCopy()).put("name","다른 이름"));assertThatCode(()->validator.validate(prepared,check,"NO_CONFLICT",body)).doesNotThrowAnyException();
    }
    @Test @DisplayName("고정 기준과 다른 subject_ref, 없는 entry_ref, 중복 ref는 거절한다") void wrongSettingRowsRejected() {
        subjectS1(row("B1","S1"),row("B3","S2"));invalid();
        subjectS1(row("B1","S1"),json.createObjectNode().put("entry_ref","B3"));invalid();
        subjectS1(row("B1","S1"),row("B3","S1"),row("B9","S1"));invalid();
        subjectS1(row("B1","S1"),row("B3","S1"),row("B1","S1"));invalid();
    }
    @Test @DisplayName("settings ref 집합은 질문 대상의 모든 항목과 같아야 한다") void settingRefSetMustEqualNeeded() {
        subjectS1(row("B1","S1"));invalid();
        subjectS1(row("B1","S1"),row("B2","S2"),row("B3","S1"));invalid();
    }
    ObjectNode row(String entry,String subject) {return json.createObjectNode().put("entry_ref",entry).put("subject_ref",subject);}
    /** projectionVector 고정 기준(B1·B3=S1, B2=S2)과 S1 질문으로 바꾼다. 질문에 필요한 settings는 B1·B3이다. */
    void subjectS1(JsonNode... rows) {
        var c=prepared.context();prepared=new ValidationPublicationValidator.Prepared(new ValidationPublicationValidator.Context(c.jobId(),c.lease(),c.scope(),c.member(),c.work(),c.baselineHash(),c.targetHash(),c.configuration(),f.path("projectionVector").path("entries"),c.sourceReferences()),prepared.branch(),prepared.grid(),prepared.sources(),0);
        var draft=(ObjectNode)state.path("draft");draft.set("subject_refs",json.createArrayNode().add("S1"));
        check=ValidationCheck.create(mock(ValidationReport.class),"Q1",json.createArrayNode().add("PLANNER:TSEG1:local-1"),state,journal.hash(state));
        var bundle=(ObjectNode)body.path("evidence_bundle");bundle.set("question",draft.deepCopy());bundle.set("settings",json.createArrayNode().addAll(List.of(rows)));
    }
    void invalid() {assertThatThrownBy(()->validator.validate(prepared,check,"NO_CONFLICT",body)).isInstanceOf(AppException.class);}
    @Test @DisplayName("판정은 현재값만 받아 같은 주체의 worldHistory 항목은 bundle settings에 들어가지 않는다")
    void worldHistoryEntriesAreNeverBundleSettings() {
        var entries=f.path("subjectCatalogueVector").path("entries");
        var c=prepared.context();prepared=new ValidationPublicationValidator.Prepared(new ValidationPublicationValidator.Context(c.jobId(),c.lease(),c.scope(),c.member(),c.work(),c.baselineHash(),c.targetHash(),c.configuration(),entries,c.sourceReferences()),prepared.branch(),prepared.grid(),prepared.sources(),prepared.entryCount());
        var draft=(ObjectNode)state.path("draft");draft.set("subject_refs",json.createArrayNode().add("S2"));
        check=ValidationCheck.create(mock(ValidationReport.class),"Q1",json.createArrayNode().add("PLANNER:TSEG1:local-1"),state,journal.hash(state));
        var bundle=(ObjectNode)body.path("evidence_bundle");bundle.set("question",draft.deepCopy());
        bundle.set("settings",json.createArrayNode().add(row("B2","S2")));assertThatCode(()->validator.validate(prepared,check,"NO_CONFLICT",body)).doesNotThrowAnyException();
        bundle.set("settings",json.createArrayNode().add(row("B2","S2")).add(row("B4","S2")));invalid();
    }
}
