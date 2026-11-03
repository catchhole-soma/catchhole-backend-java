package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** #349 결정 ③(2026-11-02). 원고·질문·판정은 모두 합성 값이다. */
@SpringBootTest(properties="spring.config.import=") @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@DisplayName("질문 하나의 INCOMPLETE 게시와 나머지 판정·작가 재시도 (#349 ③)")
class ValidationQuestionIncompleteIntegrationTest extends ValidationWorkerFixture {
    final Map<String,Object> span1=Map.of("source_ref","T1","start",0,"end",2,"quote","처음"),span2=Map.of("source_ref","T1","start",3,"end",5,"quote","합성");
    Map<String,Object> citation(Map<String,Object> span) {return Map.of("sourceRef","T1","sourceHash",target.getContentHash(),"sourceVersion",target.getContentS3Version(),"start",span.get("start"),"end",span.get("end"),"quote",span.get("quote"));}
    Map<String,Object> contextCitation() {return Map.of("sourceRef","T1","sourceHash",target.getContentHash(),"sourceVersion",target.getContentS3Version(),"start",0,"end",8,"quote","처음 합성 원고");}
    Map<String,Object> d1,d2;
    JsonNode plan(JsonNode job) throws Exception {
        d1=draft("local-1",List.of(span1));d2=draft("local-2",List.of(span2));d2.put("property_or_rule","level");
        var planner=publication(job,"SETTING","PLANNER","TSEG1",batch("TSEG1",List.of(d1,d2)));planner.put("citations",List.of(citation(span1),citation(span2)));internal(path(job,"/plans"),job,planner,200);
        var merged=publication(job,"SETTING","MERGED_PLAN","PLAN",Map.of("questions",List.of(
                Map.of("localRef","Q1","originalRefs",List.of("PLANNER:TSEG1:local-1"),"state",Map.of("draft",d1,"lookup_obligations",List.of())),
                Map.of("localRef","Q2","originalRefs",List.of("PLANNER:TSEG1:local-2"),"state",Map.of("draft",d2,"lookup_obligations",List.of())))));
        merged.put("citations",List.of(citation(span1),citation(span2)));
        return internal(path(job,"/plans"),job,merged,200).path("checks");
    }
    JsonNode check(JsonNode checks,String ref) {for(var c:checks)if(c.path("localRef").asText().equals(ref))return c;throw new AssertionError(ref);}
    Map<String,Object> decided(JsonNode job,JsonNode check,String ref,Map<String,Object> draft,Map<String,Object> span,String key) {
        var assessment=Map.of("reason","실제 확인","evidence_refs",List.of("T1"),"receipt_refs",List.of("L1","L2"));
        var bundle=evidenceBundle(draft);bundle.put("question_ref",ref);bundle.put("check_revision",check.path("revision").asLong());
        var decision=new LinkedHashMap<String,Object>();decision.put("action","DECIDED");decision.put("outcome","NO_CONFLICT");decision.put("basis","COMPATIBLE");decision.put("reason","같은 문맥에서 정상");
        decision.put("target_spans",List.of(span));decision.put("evidence_refs",List.of("T1"));decision.put("completion",Map.of("comparison_context",assessment,"changes_exceptions",assessment,"no_pending_clues",assessment,"required_reads",assessment));
        decision.put("follow_ups",List.of());decision.put("affected_question_refs",List.of());decision.put("disagreements",List.of());decision.put("evidence_bundle",bundle);
        var unit=unit(job,"SETTING",ref,"NO_CONFLICT",decision);unit.put("idempotencyKey",key);unit.put("checkId",check.path("checkId").asText());unit.put("expectedCheckRevision",check.path("revision").asLong());
        unit.put("checkInputDigest",check.path("checkInputDigest").asText());unit.put("citations",List.of(citation(span),contextCitation()));return unit;
    }
    Map<String,Object> incomplete(JsonNode job,JsonNode check,Map<String,Object> payload,List<?> citations,String key) {
        var unit=unit(job,"SETTING",check.path("localRef").asText(),"INCOMPLETE",payload);unit.put("idempotencyKey",key);unit.put("checkId",check.path("checkId").asText());
        unit.put("expectedCheckRevision",check.path("revision").asLong());unit.put("checkInputDigest",check.path("checkInputDigest").asText());unit.put("citations",citations);return unit;
    }
    JsonNode results(JsonNode job) throws Exception {
        var body=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base()+"/"+job.path("scope").path("reportId").asText()+"/results").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    @Test @DisplayName("실패한 질문만 INCOMPLETE로 저장되고 실행은 PARTIAL이며, 재시도는 그 질문만 같은 revision에서 다시 결론 내고 게시된 판정은 지킨다")
    void oneIncompleteQuestionKeepsTheOthersAndOnlyItIsJudgedAgain() throws Exception {
        source();var report=start();var job=claim();var checks=plan(job);var q1=check(checks,"Q1");var q2=check(checks,"Q2");
        internal(path(job,"/units"),job,decided(job,q1,"Q1",d1,span1,"q1-decided"),200);
        // Strict payload: the question's own spans (cited), safe codes, the failed call, nothing else.
        internal(path(job,"/units"),job,incomplete(job,q2,incompletePayload(List.of(span1)),List.of(citation(span1)),"wrong-span"),400);
        var extra=incompletePayload(List.of(span2));extra.put("reason","자유 문장");internal(path(job,"/units"),job,incomplete(job,q2,extra,List.of(citation(span2)),"extra-key"),400);
        var unsafe=incompletePayload(List.of(span2));unsafe.put("failure_code","bad code");internal(path(job,"/units"),job,incomplete(job,q2,unsafe,List.of(citation(span2)),"unsafe-code"),400);
        var call=incompletePayload(List.of(span2));call.put("failed_call",4);internal(path(job,"/units"),job,incomplete(job,q2,call,List.of(citation(span2)),"call-out-of-range"),400);
        internal(path(job,"/units"),job,incomplete(job,q2,incompletePayload(List.of(span2)),List.of(),"uncited"),400);
        var saved=internal(path(job,"/units"),job,incomplete(job,q2,incompletePayload(List.of(span2)),List.of(citation(span2)),"q2-incomplete"),200);
        assertThat(saved.path("outcome").asText()).isEqualTo("INCOMPLETE");
        assertThat(em.createNativeQuery("select count(*) from validation_findings").getSingleResult()).as("INCOMPLETE는 finding이 아니다").isEqualTo(0L);

        var progress=results(job);
        assertThat(progress.path("completedChecks").asInt()).isEqualTo(1);assertThat(progress.path("incompleteChecks").asInt()).isEqualTo(1);assertThat(progress.path("totalChecks").asInt()).isEqualTo(2);
        JsonNode view=null;for(var c:progress.path("checks"))if(c.path("outcome").asText().equals("INCOMPLETE"))view=c;
        assertThat(view).isNotNull();assertThat(view.path("failureCode").asText()).isEqualTo("MODEL_CONTRACT_INVALID");assertThat(view.path("failureCause").asText()).isEqualTo("MODEL_CONTRACT_JSON_INVALID");
        assertThat(view.path("targetSpans").path(0).path("quote").asText()).isEqualTo("합성");
        assertThat(progress.toString()).doesNotContain("evidence_bundle","checkInputDigest","leaseToken");

        internal(path(job,"/units"),job,unit(job,"TYPO","TSEG1","COMPLETED",typoEmpty()),200);
        var done=internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","questions-incomplete","failureCode","SETTING_QUESTIONS_INCOMPLETE"),200);
        assertThat(done.path("status").asText()).as("INCOMPLETE 질문이 남으면 깨끗한 성공이 아니다").isEqualTo("PARTIAL");
        assertThat(done.path("failureCode").asText()).isEqualTo("SETTING_QUESTIONS_INCOMPLETE");assertThat(done.path("settingStatus").asText()).isEqualTo("PARTIAL");assertThat(done.path("typoStatus").asText()).isEqualTo("COMPLETED");
        noExtraction();

        var current=get(UUID.fromString(report.path("reportId").asText()));
        postOk("/"+report.path("reportId").asText()+"/retries",Map.of("expectedReportVersion",current.path("reportVersion").asLong(),"idempotencyKey","retry-incomplete"));
        var next=claim();var resumed=next.path("checkpoint").path("checks");
        assertThat(check(resumed,"Q1").path("outcome").asText()).isEqualTo("NO_CONFLICT");
        assertThat(check(resumed,"Q2").path("outcome").asText()).isEqualTo("INCOMPLETE");assertThat(check(resumed,"Q2").path("revision").asLong()).isEqualTo(1);
        internal(path(next,"/units"),next,decided(next,check(resumed,"Q2"),"Q2",d2,span2,"q2-decided"),200);
        var other=decided(next,check(resumed,"Q1"),"Q1",d1,span1,"q1-changed");((Map<String,Object>)other.get("payload")).put("reason","다른 판정");
        internal(path(next,"/units"),next,other,409);
        var finished=internal(path(next,"/complete"),next,Map.of("scope",scope(next),"idempotencyKey","after-retry"),200);
        assertThat(finished.path("status").asText()).isEqualTo("COMPLETED");assertThat(finished.path("incompleteChecks").asInt()).isZero();assertThat(finished.path("completedChecks").asInt()).isEqualTo(2);
    }
    @Test @DisplayName("질문 INCOMPLETE만 남고 다른 성공이 없으면 FAILED이며 실행 단위 코드를 그대로 보존한다")
    void onlyIncompleteQuestionsWithoutAnySuccessIsFailed() throws Exception {
        source();start();var job=claim();var checks=plan(job);
        internal(path(job,"/units"),job,incomplete(job,check(checks,"Q1"),incompletePayload(List.of(span1)),List.of(citation(span1)),"q1-incomplete"),200);
        internal(path(job,"/units"),job,incomplete(job,check(checks,"Q2"),incompletePayload(List.of(span2)),List.of(citation(span2)),"q2-incomplete"),200);
        var done=internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","all-incomplete","failureCode","BUDGET_EXHAUSTED"),200);
        assertThat(done.path("status").asText()).isEqualTo("FAILED");assertThat(done.path("failureCode").asText()).isEqualTo("BUDGET_EXHAUSTED");assertThat(done.path("incompleteChecks").asInt()).isEqualTo(2);noExtraction();
    }
}
