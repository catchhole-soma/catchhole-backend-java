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
    void invalid() {assertThatThrownBy(()->validator.validate(prepared,check,"NO_CONFLICT",body)).isInstanceOf(AppException.class);}
}
