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
    @Test @DisplayName("계획의 조회 의무를 빈 실행 목록으로 지울 수 없다") void stateCannotDropPlannedObligations() {
        ((ObjectNode)state.path("draft")).set("lookup_intents",f.path("requests").path("revisions").path("changes").path(0).path("state").path("lookup_obligations"));
        assertThatThrownBy(()->validator.state(state,Set.of())).isInstanceOf(AppException.class);
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
    void conflictBundle(String source,String outcome,String basis) {
        var refs=Map.<String,JsonNode>of("T1",json.createObjectNode().put("sourceKind","EPISODE").put("episodeNo",101),"R1",json.createObjectNode().put("sourceKind","EPISODE").put("episodeNo",100));
        var c=prepared.context();prepared=new ValidationPublicationValidator.Prepared(new ValidationPublicationValidator.Context(c.jobId(),c.lease(),c.scope(),c.member(),c.work(),c.baselineHash(),c.targetHash(),c.configuration(),c.entries(),refs),prepared.branch(),prepared.grid(),prepared.sources(),0);
        var bundle=(ObjectNode)body.path("evidence_bundle");bundle.set("source_catalogue",mapper.toValidationSourceCatalogue(refs));
        ((ArrayNode)bundle.path("evidence")).addObject().put("ref","E1").put("source_ref",source).put("source_kind","EPISODE").put("start",0).put("end",2).put("quote","😀가");
        var receipt=((ArrayNode)bundle.path("receipts")).addObject().put("ref","L3").put("status","SUCCEEDED").put("limited",false);
        receipt.set("intent",json.createObjectNode().put("ref","CHANGES-1").put("kind","CHANGES_EXCEPTIONS").put("operation","KEYWORD").put("purpose","합성 변경 조회").set("evidence_refs",json.createArrayNode().add("E1")));
        receipt.set("evidence_refs",json.createArrayNode().add("E1"));
        for(var assessment:body.path("completion"))((ObjectNode)assessment).set("receipt_refs",json.createArrayNode().add("L1").add("L2").add("L3"));
        body.put("outcome",outcome).put("basis",basis).put("impact","CAUTION").put("impact_reason","합성 비교 차이");body.set("evidence_refs",json.createArrayNode().add("T1").add("E1"));
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
}
