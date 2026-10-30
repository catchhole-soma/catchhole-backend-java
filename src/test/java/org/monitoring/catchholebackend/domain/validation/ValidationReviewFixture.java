package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Synthetic worker publications and public review API drivers shared by GH312 S1 judgments and S2 review completion. */
abstract class ValidationReviewFixture extends ValidationWorkerFixture {
    static final Map<String,Object> SPAN=Map.of("source_ref","T1","start",0,"end",2,"quote","처음");
    @Autowired org.monitoring.catchholebackend.domain.work.repository.WorkPurgeDataRepository workPurge;
    String priorHash;

    // ---- synthetic execution with real stored findings ----
    JsonNode begin() throws Exception {
        source();priorHash=sha("합성 앞 원고");prior.updateContentStorage(prior.getContentS3Key(),prior.getContentS3Version(),priorHash);em.flush();start();return claim();
    }
    String sha(String text) throws Exception {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    Map<String,Object> cite(int start,int end,String quote) {return Map.of("sourceRef","T1","sourceHash",target.getContentHash(),"sourceVersion",target.getContentS3Version(),"start",start,"end",end,"quote",quote);}
    Map<String,Object> priorCite() {return Map.of("sourceRef","R1","sourceHash",priorHash,"sourceVersion",prior.getContentS3Version(),"start",0,"end",2,"quote","합성");}
    UUID reportId(JsonNode job) {return UUID.fromString(job.path("scope").path("reportId").asText());}
    /** Saves planner/selection/coverage and returns the server-issued checks for Q1..Qn. */
    List<JsonNode> plan(JsonNode job,int count) throws Exception {
        var drafts=new ArrayList<Object>();for(int i=1;i<=count;i++)drafts.add(draft("local-"+i,List.of(SPAN)));
        var planner=publication(job,"SETTING","PLANNER","TSEG1",batch("TSEG1",drafts));planner.put("citations",List.of(cite(0,2,"처음")));internal(path(job,"/plans"),job,planner,200);
        int entries=stored(get(reportId(job))).getBaselineEntries().size();var selected=new ArrayList<Object>();for(int i=1;i<=entries;i++)selected.add(Map.of("entry_ref","B"+i,"selected",false,"reason","합성 비선정"));
        savePlan(job,"RELATED_SELECTION","TSEG1:PAGE1",Map.of("entries",selected));savePlan(job,"COVERAGE","TSEG1",batch("TSEG1",List.of()));
        var questions=new ArrayList<Object>();for(int i=1;i<=count;i++)questions.add(Map.of("localRef","Q"+i,"originalRefs",List.of("PLANNER:TSEG1:local-"+i),"state",Map.of("draft",drafts.get(i-1))));
        var merged=publication(job,"SETTING","MERGED_PLAN","PLAN",Map.of("questions",questions));merged.put("citations",List.of(cite(0,2,"처음")));
        var checks=new ArrayList<JsonNode>();internal(path(job,"/plans"),job,merged,200).path("checks").forEach(checks::add);return checks;
    }
    Map<String,Object> conflict(JsonNode check,boolean newClue) {
        var draft=draft("local-"+check.path("localRef").asText().substring(1),List.of(SPAN));var bundle=evidenceBundle(draft);
        bundle.put("question_ref",check.path("localRef").asText());bundle.put("question_refs",List.of(check.path("localRef").asText()));bundle.put("check_revision",check.path("revision").asLong());
        bundle.put("evidence",List.of(Map.of("ref","E1","source_ref","R1","source_kind","EPISODE","start",0,"end",2,"quote","합성")));
        var receipts=new ArrayList<Object>((List<?>)bundle.get("receipts"));if(newClue)receipts.add(Map.of("ref","L3","intent",lookup("NEW-1","NEW_CLUE","TARGET_CONTEXT"),"status","SUCCEEDED","evidence_refs",List.of(),"limited",false));bundle.put("receipts",receipts);
        var assessment=Map.of("reason","실제 고정 자료 확인","evidence_refs",List.of("T1","E1"),"receipt_refs",List.of("L1","L2"));
        var reads=Map.of("reason","필수 읽기 완료","evidence_refs",List.of("T1","E1"),"receipt_refs",newClue?List.of("L1","L2","L3"):List.of("L1","L2"));
        var d=new LinkedHashMap<String,Object>();d.put("action","DECIDED");d.put("outcome","CONFLICT");d.put("basis","DIRECT_CONTRADICTION");d.put("reason","합성 충돌 판정");d.put("impact","CAUTION");d.put("impact_reason","비교값 차이");
        d.put("target_spans",List.of(SPAN));d.put("evidence_refs",List.of("T1","E1"));d.put("completion",Map.of("comparison_context",assessment,"changes_exceptions",assessment,"no_pending_clues",assessment,"required_reads",reads));
        d.put("follow_ups",List.of());d.put("affected_question_refs",List.of());d.put("disagreements",List.of());d.put("evidence_bundle",bundle);return d;
    }
    void decide(JsonNode job,JsonNode check,boolean newClue) throws Exception {
        var command=unit(job,"SETTING",check.path("localRef").asText(),"CONFLICT",conflict(check,newClue));command.put("idempotencyKey","unit-"+check.path("localRef").asText()+"-r"+check.path("revision").asLong());
        command.put("checkId",check.path("checkId").asText());command.put("expectedCheckRevision",check.path("revision").asLong());command.put("checkInputDigest",check.path("checkInputDigest").asText());
        command.put("citations",List.of(cite(0,2,"처음"),cite(0,8,"처음 합성 원고"),priorCite()));internal(path(job,"/units"),job,command,200);
    }
    void typos(JsonNode job) throws Exception {
        var suggestions=List.of(Map.of("category","SPACING","reason","띄어쓰기 제안","replacement","처 음","span",SPAN),
                Map.of("category","SPELLING","reason","맞춤법 제안","replacement","원교","span",Map.of("source_ref","T1","start",6,"end",8,"quote","원고")));
        var command=unit(job,"TYPO","TSEG1","COMPLETED",Map.of("segments",List.of(Map.of("segment_ref","TSEG1","status","PLANNED","reason","제안 있음")),"suggestions",suggestions));
        command.put("citations",List.of(cite(0,2,"처음"),cite(6,8,"원고")));internal(path(job,"/units"),job,command,200);
    }
    String finish(JsonNode job,String key) throws Exception {return internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey",key),200).path("status").asText();}
    /** COMPLETED report with {@code checks} SETTING findings and two TYPO findings. */
    JsonNode completedWithFindings(int checks) throws Exception {
        var job=begin();for(var check:plan(job,checks))decide(job,check,false);typos(job);assertThat(finish(job,"done")).isEqualTo("COMPLETED");return job;
    }
    void retry(UUID report,String key) throws Exception {postOk("/"+report+"/retries",Map.of("expectedReportVersion",get(report).path("reportVersion").asLong(),"idempotencyKey",key));}
    /** 실제 확정 이력 대신 mode만 바꾼 합성 진단 report의 실행을 시작한다. */
    JsonNode diagnosticStart() throws Exception {
        source();start();var job=claim();em.createNativeQuery("update validation_reports set mode='DIAGNOSTIC_ONLY' where id=:r").setParameter("r",reportId(job)).executeUpdate();em.clear();return job;
    }
    UUID zeroFindingCompletion() throws Exception {
        source();start();var job=claim();prePlan(job,List.of());savePlan(job,"MERGED_PLAN","PLAN",Map.of("questions",List.of()));
        internal(path(job,"/units"),job,unit(job,"TYPO","TSEG1","COMPLETED",typoEmpty()),200);assertThat(finish(job,"empty")).isEqualTo("COMPLETED");return reportId(job);
    }

    // ---- public review API ----
    JsonNode read(String path,String token,int status) throws Exception {
        var result=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base()+path).header("Authorization","Bearer "+token)).andExpect(status().is(status)).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path(status==200?"data":"error");
    }
    JsonNode findings(UUID report,String query) throws Exception {return read("/"+report+"/findings"+query,token,200);}
    JsonNode only(UUID report,String branch) throws Exception {return findings(report,"?branch="+branch).path("content").path(0);}
    JsonNode detail(UUID report,JsonNode finding) throws Exception {return read("/"+report+"/findings/"+finding.path("findingId").asText(),token,200);}
    JsonNode review(UUID report) throws Exception {return read("/"+report+"/review",token,200);}
    JsonNode save(UUID report,JsonNode finding,String value,Long version,Long revision,int status) throws Exception {
        var body=new HashMap<String,Object>();body.put("judgment",value);body.put("expectedJudgmentVersion",version);body.put("expectedCheckRevision",revision);
        var result=mvc.perform(patch(base()+"/"+report+"/findings/"+finding.path("findingId").asText()+"/judgment").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is(status)).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path(status==200?"data":"error");
    }
    /** Uses the finding's own judgment version and check revision. */
    JsonNode judge(UUID report,JsonNode finding,String value) throws Exception {
        long version=finding.path("judgment").path("version").asLong();
        return save(report,finding,value,version==0?null:version,finding.path("checkRevision").isNull()?null:finding.path("checkRevision").asLong(),200);
    }
    String code(JsonNode error) {return error.path("code").asText();}
    long count(String table,UUID report) {return ((Number)em.createNativeQuery("select count(*) from "+table+" where report_id=:r").setParameter("r",report).getSingleResult()).longValue();}
    List<String> texts(JsonNode array) {var out=new ArrayList<String>();array.forEach(n->out.add(n.asText()));return out;}
}
