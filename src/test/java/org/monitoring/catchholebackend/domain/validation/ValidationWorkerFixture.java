package org.monitoring.catchholebackend.domain.validation;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.http.MediaType;

/** Internal worker API drivers shared by execution and author-review tests. All numeric limits are synthetic fixtures. */
abstract class ValidationWorkerFixture extends ValidationRecheckFixture {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties executionProperties;
    static final String WORKER="/api/internal/v1/validation-jobs";
    static final String KEY="local-development-internal-api-key";
    JsonNode claim() throws Exception { return internal("/claim",null,Map.of("contractVersion",1,"workerId","synthetic-worker","capabilities",List.of("VALIDATION_EXECUTION_V1")),200); }
    JsonNode internal(String path,JsonNode job,Object body,int status) throws Exception {
        var request=post(WORKER+path).header("X-Internal-Api-Key",KEY).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if(job!=null)request.header("X-Worker-Lease-Token",job.path("leaseToken").asText());
        var result=mvc.perform(request).andExpect(status().is(status)).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path(status==200?"data":"error");
    }
    Map<String,Object> scope(JsonNode job) { return json.convertValue(job.path("scope"),new com.fasterxml.jackson.core.type.TypeReference<>(){}); }
    String path(JsonNode job,String suffix) { return "/"+job.path("jobId").asText()+suffix; }
    Map<String,Object> publication(JsonNode job,String branch,String stage,String ref,Object payload) {
        return new LinkedHashMap<>(Map.of("scope",scope(job),"idempotencyKey",stage+":"+ref,"branch",branch,"stage",stage,"ref",ref,"payload",payload,"citations",List.of()));
    }
    void source() { when(storage.getText(anyString())).thenAnswer(c-> c.getArgument(0).equals(target.getContentS3Key())?"처음 합성 원고":"합성 앞 원고"); }
    Map<String,Object> batch(String ref,List<?> questions) {return Map.of("questions",questions,"segments",List.of(Map.of("segment_ref",ref,"status",questions.isEmpty()?"NO_CHECK":"PLANNED","reason","합성 완료")),"rules",List.of());}
    JsonNode savePlan(JsonNode job,String stage,String ref,Object body) throws Exception {return internal(path(job,"/plans"),job,publication(job,"SETTING",stage,ref,body),200);}
    void prePlan(JsonNode job,List<?> questions) throws Exception {
        savePlan(job,"PLANNER","TSEG1",batch("TSEG1",questions));
        int entries=stored(get(UUID.fromString(job.path("scope").path("reportId").asText()))).getBaselineEntries().size();
        List<Object> selected=new ArrayList<>();for(int i=1;i<=entries;i++)selected.add(Map.of("entry_ref","B"+i,"selected",false,"reason","합성 비선정"));
        savePlan(job,"RELATED_SELECTION","TSEG1:PAGE1",Map.of("entries",selected));savePlan(job,"COVERAGE","TSEG1",batch("TSEG1",List.of()));
    }
    Map<String,Object> unit(JsonNode job,String branch,String ref,String outcome,Object body) {
        return new LinkedHashMap<>(Map.of("scope",scope(job),"idempotencyKey","unit-"+branch+"-"+ref,"branch",branch,"ref",ref,"outcome",outcome,"payload",body,"citations",List.of()));
    }
    Object typoEmpty() {return Map.of("segments",List.of(Map.of("segment_ref","TSEG1","status","NO_CHECK","reason","오탈자 없음")),"suggestions",List.of());}
    Map<String,Object> draft(String ref,List<?> spans) {
        var d=new LinkedHashMap<String,Object>();d.put("local_ref",ref);d.put("target_spans",spans);d.put("subject_refs",List.of());d.put("subject_mentions",List.of("합성 신규 내용"));d.put("property_or_rule","age");d.put("time_context","현재");d.put("conditions",List.of());d.put("assertion_mode","NARRATION");d.put("check_intent","고정 기준과 대조");d.put("lookup_intents",List.of());d.put("reason","합성 계획");return d;
    }
    Map<String,Object> lookup(String ref,String kind,String operation) {
        var i=new LinkedHashMap<String,Object>();i.put("ref",ref);i.put("kind",kind);i.put("operation",operation);i.put("purpose","고정 자료 확인");i.put("query",null);i.put("source_ref",null);i.put("start",null);i.put("end",null);i.put("evidence_refs",List.of());return i;
    }
    Map<String,Object> evidenceBundle(Object draft) {
        var b=new LinkedHashMap<String,Object>();b.put("question_ref","Q1");b.put("check_revision",1);b.put("question",draft);b.put("target_context",List.of(Map.of("start",0,"text","처음 합성 원고")));b.put("settings",List.of());b.put("evidence",List.of());b.put("question_refs",List.of("Q1"));b.put("additional_target_context",List.of());b.put("source_catalogue",List.of(Map.of("source_ref","R1","source_kind","EPISODE","episodeNo",100)));
        b.put("receipts",List.of(Map.of("ref","L1","intent",lookup("Q1-BASELINE","COMPARISON_CONTEXT","BASELINE"),"status","SUCCEEDED","evidence_refs",List.of(),"limited",false),Map.of("ref","L2","intent",lookup("Q1-TARGET_CONTEXT","COMPARISON_CONTEXT","TARGET_CONTEXT"),"status","SUCCEEDED","evidence_refs",List.of("T1"),"limited",false)));b.put("input_digest","0".repeat(64));return b;
    }
}
