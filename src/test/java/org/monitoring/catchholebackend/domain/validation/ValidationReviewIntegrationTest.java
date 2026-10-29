package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** Synthetic worker publications drive real findings; judgments never create extraction jobs or candidates. */
@SpringBootTest(properties="spring.config.import=") @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@DisplayName("GH312 S1 작가 판단 저장·조회와 검토 요약")
class ValidationReviewIntegrationTest extends ValidationWorkerFixture {
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

    @Test @DisplayName("판단은 검사 실행·재시도 대기 중 잠기고 종료·중단 뒤에만 저장된다")
    void judgmentLockFollowsExecutionWindow() throws Exception {
        var job=begin();decide(job,plan(job,1).getFirst(),false);var report=reportId(job);var finding=only(report,"SETTING");
        assertThat(code(save(report,finding,"NOT_CONFLICT",null,1L,409))).isEqualTo("VALIDATION_JUDGMENT_LOCKED");
        var running=review(report);assertThat(running.path("judgmentLocked").asBoolean()).isTrue();assertThat(running.path("lockReason").asText()).isEqualTo("EXECUTION_IN_PROGRESS");
        assertThat(texts(running.path("allowedActions"))).doesNotContain("SAVE_JUDGMENT");
        assertThat(finish(job,"typo-missing")).isEqualTo("PARTIAL");
        var partial=judge(report,finding,"NOT_CONFLICT");assertThat(partial.path("judgment").path("value").asText()).isEqualTo("NOT_CONFLICT");
        retry(report,"requeue");finding=only(report,"SETTING");
        assertThat(code(save(report,finding,"NEEDS_FIX",1L,1L,409))).isEqualTo("VALIDATION_JUDGMENT_LOCKED");
        assertThat(review(report).path("lockReason").asText()).isEqualTo("EXECUTION_IN_PROGRESS");
        var next=claim();assertThat(code(save(report,finding,"NEEDS_FIX",1L,1L,409))).isEqualTo("VALIDATION_JUDGMENT_LOCKED");
        assertThat(only(report,"SETTING").path("judgment").path("value").asText()).as("same-input retry keeps the unchanged judgment").isEqualTo("NOT_CONFLICT");
        internal(path(next,"/units"),next,unit(next,"TYPO","TSEG1","COMPLETED",typoEmpty()),200);assertThat(finish(next,"done")).isEqualTo("COMPLETED");
        assertThat(judge(report,only(report,"SETTING"),"NEEDS_FIX").path("judgment").path("version").asLong()).isEqualTo(2);noExtraction();
    }

    @Test @DisplayName("lease 회수 한도를 넘겨 FAILED가 된 검사도 판단을 저장할 수 있다")
    void judgmentAllowedAfterFailedExecution() throws Exception {
        var job=begin();decide(job,plan(job,1).getFirst(),false);var report=reportId(job);
        for(int i=0;i<3;i++) {em.createNativeQuery("update validation_jobs set lease_expires_at=CURRENT_TIMESTAMP - interval '1' second").executeUpdate();em.clear();claim();}
        assertThat(em.createNativeQuery("select status from validation_jobs").getSingleResult()).isEqualTo("FAILED");
        assertThat(review(report).path("judgmentLocked").asBoolean()).isFalse();
        assertThat(judge(report,only(report,"SETTING"),"NOT_CONFLICT").path("judgment").path("value").asText()).isEqualTo("NOT_CONFLICT");
        var summary=review(report);assertThat(summary.path("completionMode").asText()).isEqualTo("BLOCKED");assertThat(texts(summary.path("holdReasons"))).containsExactly("EXECUTION_NOT_COMPLETED");noExtraction();
    }

    @Test @DisplayName("분기별 판단 값·check revision·판단 version을 검증하고 같은 값은 멱등이다")
    void judgmentValidationCasAndIdempotency() throws Exception {
        var report=reportId(completedWithFindings(1));var setting=only(report,"SETTING");var typo=only(report,"TYPO");
        assertThat(code(save(report,setting,"KEEP_ORIGINAL",null,1L,400))).isEqualTo("VALIDATION_JUDGMENT_INVALID");
        assertThat(code(save(report,typo,"NOT_CONFLICT",null,null,400))).isEqualTo("VALIDATION_JUDGMENT_INVALID");
        assertThat(code(save(report,typo,"APPROVED",null,null,400))).isEqualTo("REQUEST_INVALID_ARGUMENT");
        assertThat(code(save(report,typo,null,null,null,400))).isEqualTo("REQUEST_VALIDATION_FAILED");
        assertThat(code(save(report,setting,"NOT_CONFLICT",null,2L,409))).isEqualTo("VALIDATION_JUDGMENT_STALE");
        assertThat(code(save(report,setting,"NOT_CONFLICT",null,null,409))).isEqualTo("VALIDATION_JUDGMENT_STALE");
        assertThat(code(save(report,typo,"KEEP_ORIGINAL",null,1L,409))).isEqualTo("VALIDATION_JUDGMENT_STALE");
        assertThat(code(save(report,setting,"NOT_CONFLICT",1L,1L,409))).isEqualTo("VALIDATION_JUDGMENT_VERSION_CONFLICT");
        assertThat(count("validation_judgments",report)).isZero();
        long before=get(report).path("reportVersion").asLong();
        var first=save(report,setting,"NOT_CONFLICT",0L,1L,200);
        assertThat(first.path("judgment").path("version").asLong()).isEqualTo(1);assertThat(first.path("judgment").path("decidedAt").isNull()).isFalse();
        assertThat(get(report).path("reportVersion").asLong()).isEqualTo(before+1);assertThat(count("validation_judgment_history",report)).isEqualTo(1);
        var replay=save(report,setting,"NOT_CONFLICT",1L,1L,200);
        assertThat(replay.path("judgment")).isEqualTo(first.path("judgment"));assertThat(get(report).path("reportVersion").asLong()).isEqualTo(before+1);assertThat(count("validation_judgment_history",report)).isEqualTo(1);
        assertThat(code(save(report,setting,"NEEDS_FIX",null,1L,409))).isEqualTo("VALIDATION_JUDGMENT_VERSION_CONFLICT");
        assertThat(code(save(report,setting,"NEEDS_FIX",2L,1L,409))).isEqualTo("VALIDATION_JUDGMENT_VERSION_CONFLICT");
        var reverted=save(report,setting,"PENDING",1L,1L,200);
        assertThat(reverted.path("judgment").path("value").asText()).isEqualTo("PENDING");assertThat(reverted.path("judgment").path("version").asLong()).isEqualTo(2);assertThat(reverted.path("judgment").path("decidedAt").isNull()).isTrue();
        assertThat(count("validation_judgments",report)).isEqualTo(1);assertThat(count("validation_judgment_history",report)).isEqualTo(2);
        var untouched=save(report,typo,"PENDING",null,null,200);
        assertThat(untouched.path("judgment").path("version").asLong()).isZero();assertThat(count("validation_judgments",report)).isEqualTo(1);assertThat(get(report).path("reportVersion").asLong()).isEqualTo(before+2);
        var rows=em.createNativeQuery("select from_judgment,to_judgment,reason from validation_judgment_history where report_id=:r order by created_at").setParameter("r",report).getResultList();
        assertThat(rows).extracting(r->Arrays.asList((Object[])r)).containsExactly(Arrays.asList(null,"NOT_CONFLICT","AUTHOR"),Arrays.asList("NOT_CONFLICT","PENDING","AUTHOR"));noExtraction();
    }

    @Test @DisplayName("같은 입력 재시도의 질문 revision 변경은 이전 판단을 이력으로 남기고 새 항목을 PENDING으로 연다")
    void revisionSupersedesJudgmentAndNewRevisionStartsPending() throws Exception {
        var job=begin();var check=plan(job,1).getFirst();decide(job,check,false);var report=reportId(job);assertThat(finish(job,"typo-missing")).isEqualTo("PARTIAL");
        var old=only(report,"SETTING");judge(report,old,"NOT_CONFLICT");
        retry(report,"follow-up-retry");var next=claim();
        var changes=List.of(Map.of("checkId",check.path("checkId").asText(),"expectedRevision",1,"expectedInputDigest",check.path("checkInputDigest").asText(),
                "state",Map.of("draft",draft("local-1",List.of(SPAN)),"lookup_obligations",List.of(lookup("NEW-1","NEW_CLUE","TARGET_CONTEXT")),"new_clue","같은 속성의 새 단서")));
        var revised=internal(path(next,"/revisions"),next,Map.of("scope",scope(next),"idempotencyKey","follow-up","reason","NEW_CLUE","changes",changes,"citations",List.of(cite(0,2,"처음"))),200).path("checks").path(0);
        assertThat(revised.path("revision").asLong()).isEqualTo(2);
        var superseded=em.createNativeQuery("select from_judgment,to_judgment,check_revision from validation_judgment_history where reason='REVISION_SUPERSEDED'").getResultList();
        assertThat(superseded).extracting(r->Arrays.asList((Object[])r)).containsExactly(Arrays.asList("NOT_CONFLICT","PENDING",1L));
        assertThat(findings(report,"").path("totalElements").asLong()).as("stale finding leaves the current list").isZero();
        decide(next,revised,true);internal(path(next,"/units"),next,unit(next,"TYPO","TSEG1","COMPLETED",typoEmpty()),200);assertThat(finish(next,"done")).isEqualTo("COMPLETED");
        var list=findings(report,"");assertThat(list.path("totalElements").asLong()).isEqualTo(1);var current=list.path("content").path(0);
        assertThat(current.path("findingId")).isNotEqualTo(old.path("findingId"));assertThat(current.path("checkRevision").asLong()).isEqualTo(2);
        assertThat(current.path("judgment").path("value").asText()).isEqualTo("PENDING");assertThat(current.path("judgment").path("version").asLong()).isZero();assertThat(current.path("changed").asBoolean()).isTrue();
        var view=detail(report,current);assertThat(view.path("stale").asBoolean()).isFalse();assertThat(view.path("currentCheckRevision").asLong()).isEqualTo(2);
        var prior=view.path("priorRevisions").path(0);
        assertThat(prior.path("checkRevision").asLong()).isEqualTo(1);assertThat(prior.path("changeReason").asText()).isEqualTo("NEW_CLUE");assertThat(prior.path("outcome").asText()).isEqualTo("CONFLICT");
        assertThat(prior.path("findingId")).isEqualTo(old.path("findingId"));assertThat(prior.path("judgment").path("value").asText()).isEqualTo("NOT_CONFLICT");
        assertThat(view.path("judgmentHistory")).extracting(h->h.path("reason").asText()+":"+h.path("toJudgment").asText()+":"+h.path("changeReason").asText()).containsExactly("AUTHOR:NOT_CONFLICT:null","REVISION_SUPERSEDED:PENDING:NEW_CLUE");
        var stale=detail(report,old);assertThat(stale.path("stale").asBoolean()).isTrue();assertThat(stale.path("judgment").path("value").asText()).isEqualTo("NOT_CONFLICT");
        assertThat(code(save(report,old,"NEEDS_FIX",1L,1L,409))).isEqualTo("VALIDATION_JUDGMENT_STALE");
        var summary=review(report);assertThat(summary.path("counts").path("settingTotal").asLong()).isEqualTo(1);assertThat(summary.path("counts").path("settingPending").asLong()).isEqualTo(1);
        assertThat(texts(summary.path("holdReasons"))).containsExactly("PENDING_JUDGMENTS");
        assertThat(judge(report,current,"NOT_CONFLICT").path("changed").asBoolean()).isTrue();assertThat(review(report).path("completionMode").asText()).isEqualTo("EXPLICIT_REQUIRED");noExtraction();
    }

    @Test @DisplayName("같은 입력 재시도는 완료된 오탈자 finding을 다시 만들지 않고 판단을 보존한다")
    void sameInputRetryKeepsTypoFindingsAndJudgments() throws Exception {
        var job=begin();var check=plan(job,1).getFirst();typos(job);var report=reportId(job);assertThat(finish(job,"setting-missing")).isEqualTo("PARTIAL");
        var kept=findings(report,"?outcome=SPELLING").path("content").path(0);judge(report,kept,"KEEP_ORIGINAL");
        retry(report,"typo-stable");var next=claim();
        var replay=unit(next,"TYPO","TSEG1","COMPLETED",Map.of("segments",List.of(Map.of("segment_ref","TSEG1","status","PLANNED","reason","제안 있음")),"suggestions",List.of(
                Map.of("category","SPACING","reason","띄어쓰기 제안","replacement","처 음","span",SPAN),Map.of("category","SPELLING","reason","맞춤법 제안","replacement","원교","span",Map.of("source_ref","T1","start",6,"end",8,"quote","원고")))));
        replay.put("idempotencyKey","typo-new-key-exact-replay");replay.put("citations",List.of(cite(0,2,"처음"),cite(6,8,"원고")));internal(path(next,"/units"),next,replay,200);
        decide(next,check,false);assertThat(finish(next,"done")).isEqualTo("COMPLETED");
        assertThat(count("validation_findings",report)).isEqualTo(3);var after=findings(report,"?outcome=SPELLING").path("content");
        assertThat(after.size()).isEqualTo(1);assertThat(after.path(0).path("findingId")).isEqualTo(kept.path("findingId"));assertThat(after.path(0).path("judgment").path("value").asText()).isEqualTo("KEEP_ORIGINAL");noExtraction();
    }

    @Test @DisplayName("마지막 판단 저장은 명시 검토 완료 가능만 알리고 추출을 시작하지 않는다")
    void lastJudgmentDoesNotStartExtraction() throws Exception {
        var report=reportId(completedWithFindings(2));
        var pending=review(report);assertThat(pending.path("completionMode").asText()).isEqualTo("BLOCKED");assertThat(texts(pending.path("holdReasons"))).containsExactly("PENDING_JUDGMENTS");
        assertThat(texts(pending.path("allowedActions"))).contains("SAVE_JUDGMENT").doesNotContain("COMPLETE_REVIEW");assertThat(get(report).path("errorReviewStatus").asText()).isEqualTo("PENDING");
        long before=get(report).path("reportVersion").asLong();
        var all=findings(report,"").path("content");assertThat(all.size()).isEqualTo(4);
        for(var finding:all)judge(report,finding,finding.path("branch").asText().equals("SETTING")?"NOT_CONFLICT":"KEEP_ORIGINAL");
        judge(report,findings(report,"?branch=TYPO").path("content").path(0),"KEEP_ORIGINAL");
        assertThat(get(report).path("reportVersion").asLong()).as("one advance per effective change").isEqualTo(before+4);
        var ready=review(report);
        assertThat(ready.path("completionMode").asText()).isEqualTo("EXPLICIT_REQUIRED");assertThat(ready.path("canCompleteReview").asBoolean()).isTrue();assertThat(ready.path("holdReasons").size()).isZero();
        assertThat(texts(ready.path("allowedActions"))).contains("SAVE_JUDGMENT","COMPLETE_REVIEW");assertThat(ready.path("reportVersion").asLong()).isEqualTo(before+4);
        assertThat(ready.path("counts").path("settingNotConflict").asLong()).isEqualTo(2);assertThat(ready.path("counts").path("typoKeepOriginal").asLong()).isEqualTo(2);
        assertThat(ready.path("counts").path("settingPending").asLong()+ready.path("counts").path("typoPending").asLong()).isZero();
        var staging=get(report);assertThat(staging.path("errorReviewStatus").asText()).isEqualTo("READY");assertThat(staging.path("extractionStatus").asText()).isEqualTo("NOT_STARTED");assertThat(staging.path("extractionJobId").isNull()).isTrue();
        noExtraction();
    }

    @Test @DisplayName("정상 0건 완료는 자동 추출 후보, 부분 완료·수정 필요·진단 report는 보류한다")
    void reviewSummaryCompletionModes() throws Exception {
        var empty=zeroFindingCompletion();
        var automatic=review(empty);assertThat(automatic.path("completionMode").asText()).isEqualTo("AUTOMATIC_ELIGIBLE");assertThat(automatic.path("canCompleteReview").asBoolean()).isFalse();
        assertThat(automatic.path("holdReasons").size()).isZero();assertThat(texts(automatic.path("allowedActions"))).doesNotContain("SAVE_JUDGMENT","COMPLETE_REVIEW");assertThat(get(empty).path("errorReviewStatus").asText()).isEqualTo("READY");
        em.createNativeQuery("update validation_reports set mode='DIAGNOSTIC_ONLY' where id=:r").setParameter("r",empty).executeUpdate();em.clear();
        var diagnostic=review(empty);assertThat(diagnostic.path("completionMode").asText()).isEqualTo("BLOCKED");assertThat(texts(diagnostic.path("holdReasons"))).containsExactly("DIAGNOSTIC_ONLY");
        assertThat(get(empty).path("errorReviewStatus").asText()).isEqualTo("PENDING");noExtraction();
    }

    @Test @DisplayName("부분 완료와 수정 필요 판단은 검토 종료를 보류한다")
    void partialAndNeedsFixBlockReview() throws Exception {
        var job=begin();for(var check:plan(job,1))decide(job,check,false);var report=reportId(job);assertThat(finish(job,"typo-missing")).isEqualTo("PARTIAL");
        judge(report,only(report,"SETTING"),"NOT_CONFLICT");
        var partial=review(report);assertThat(partial.path("completionMode").asText()).isEqualTo("BLOCKED");assertThat(texts(partial.path("holdReasons"))).containsExactly("EXECUTION_NOT_COMPLETED");
        assertThat(texts(partial.path("allowedActions"))).contains("RETRY","SAVE_JUDGMENT");assertThat(get(report).path("errorReviewStatus").asText()).isEqualTo("NOT_STARTED");
        retry(report,"finish-typo");var next=claim();typos(next);assertThat(finish(next,"done")).isEqualTo("COMPLETED");
        for(var typo:findings(report,"?branch=TYPO").path("content"))judge(report,typo,"KEEP_ORIGINAL");
        assertThat(review(report).path("completionMode").asText()).isEqualTo("EXPLICIT_REQUIRED");
        judge(report,findings(report,"?branch=TYPO").path("content").path(1),"NEEDS_FIX");
        var fix=review(report);assertThat(fix.path("completionMode").asText()).isEqualTo("BLOCKED");assertThat(texts(fix.path("holdReasons"))).containsExactly("NEEDS_FIX_PENDING");
        assertThat(fix.path("counts").path("typoNeedsFix").asLong()).isEqualTo(1);assertThat(get(report).path("errorReviewStatus").asText()).isEqualTo("PENDING");noExtraction();
    }

    @Test @DisplayName("원고 변경 무효화와 재검수는 이전 report의 판단·이력을 지운다")
    void invalidationDeletesJudgmentsAndHistory() throws Exception {
        var report=reportId(completedWithFindings(1));var setting=only(report,"SETTING");judge(report,setting,"NOT_CONFLICT");judge(report,only(report,"TYPO"),"NEEDS_FIX");
        assertThat(count("validation_judgments",report)).isEqualTo(2);assertThat(count("validation_judgment_history",report)).isEqualTo(2);
        var original=get(report);replace("수정한 합성 원고");
        org.mockito.Mockito.when(storage.getText(org.mockito.ArgumentMatchers.anyString())).thenAnswer(c->c.getArgument(0).equals(target.getContentS3Key())?"수정한 합성 원고":"합성 앞 원고");
        for(String table:List.of("validation_judgments","validation_judgment_history","validation_findings"))assertThat(count(table,report)).as(table).isZero();
        var invalid=review(report);assertThat(invalid.path("lockReason").asText()).isEqualTo("REPORT_INVALIDATED");assertThat(texts(invalid.path("holdReasons"))).contains("REPORT_INVALIDATED");
        assertThat(texts(invalid.path("allowedActions"))).contains("RECHECK").doesNotContain("SAVE_JUDGMENT");assertThat(get(report).path("errorReviewStatus").asText()).isEqualTo("INVALIDATED");
        assertThat(read("/"+report+"/findings/"+setting.path("findingId").asText(),token,404).path("code").asText()).isEqualTo("VALIDATION_FINDING_NOT_FOUND");
        var next=postOk(recheckPath(original),recheck(original,"after-fix"));var fresh=review(UUID.fromString(next.path("reportId").asText()));
        assertThat(fresh.path("counts").path("settingTotal").asLong()+fresh.path("counts").path("typoTotal").asLong()).isZero();assertThat(texts(fresh.path("holdReasons"))).contains("EXECUTION_NOT_COMPLETED").doesNotContain("REPORT_INVALIDATED");
        assertThat(em.createNativeQuery("select count(*) from validation_judgments").getSingleResult()).isEqualTo(0L);noExtraction();
    }

    @Test @DisplayName("작품 파기는 판단·이력을 finding보다 먼저 지운다")
    void workPurgeDeletesJudgmentsBeforeFindings() throws Exception {
        var report=reportId(completedWithFindings(1));judge(report,only(report,"SETTING"),"NOT_CONFLICT");em.flush();em.clear();
        assertThat(count("validation_judgments",report)).isEqualTo(1);workPurge.purgeWorkData(work.getId());
        for(String table:List.of("validation_judgments","validation_judgment_history","validation_findings","validation_reports"))
            assertThat(em.createNativeQuery("select count(*) from "+table).getSingleResult()).as(table).isEqualTo(0L);
    }

    @Test @DisplayName("다른 회원은 발견 항목·판단·검토 요약에 접근하지 못한다")
    void anotherMemberCannotReadOrJudge() throws Exception {
        var report=reportId(completedWithFindings(1));var finding=only(report,"SETTING");
        var other=org.monitoring.catchholebackend.domain.member.entity.Member.register("other-"+UUID.randomUUID()+"@example.invalid","synthetic","01098765432","다른 작가");em.persist(other);
        String otherToken=tokens.generateAccessToken(other);String path="/"+report+"/findings/"+finding.path("findingId").asText();
        for(String suffix:List.of("/"+report+"/findings",path,"/"+report+"/review"))assertThat(read(suffix,otherToken,404).path("code").asText()).isEqualTo("WORK_NOT_FOUND");
        mvc.perform(patch(base()+path+"/judgment").header("Authorization","Bearer "+otherToken).contentType(MediaType.APPLICATION_JSON).content("{\"judgment\":\"NOT_CONFLICT\",\"expectedCheckRevision\":1}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("WORK_NOT_FOUND"));
        var otherWork=org.monitoring.catchholebackend.domain.work.entity.Work.create(member,"다른 작품",org.monitoring.catchholebackend.domain.work.type.WorkGenre.FANTASY,null);em.persist(otherWork);em.flush();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/works/"+otherWork.getId()+"/episode-validations/"+report+"/review").header("Authorization","Bearer "+token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("VALIDATION_NOT_FOUND"));
        assertThat(read("/"+report+"/findings/"+UUID.randomUUID(),token,404).path("code").asText()).isEqualTo("VALIDATION_FINDING_NOT_FOUND");
        assertThat(count("validation_judgments",report)).isZero();
    }

    @Test @DisplayName("목록은 분기·판단·결과로 거르고 페이지를 나누며 근거 행을 싣지 않는다")
    void listFiltersPaginatesAndOmitsEvidence() throws Exception {
        var report=reportId(completedWithFindings(2));
        var page=findings(report,"?size=3");assertThat(page.path("totalElements").asLong()).isEqualTo(4);assertThat(page.path("content").size()).isEqualTo(3);assertThat(page.path("hasNext").asBoolean()).isTrue();assertThat(page.path("totalPages").asInt()).isEqualTo(2);
        var second=findings(report,"?size=3&page=1");assertThat(second.path("content").size()).isEqualTo(1);assertThat(second.path("page").asInt()).isEqualTo(1);
        assertThat(findings(report,"").path("size").asInt()).isEqualTo(20);
        var first=page.path("content").path(0);
        assertThat(first.has("evidence")).isFalse();assertThat(first.has("payload")).isFalse();assertThat(first.toString()).doesNotContain("evidence_bundle","receipt","catalogue","checkInputDigest");
        assertThat(first.path("summary").path("impact").asText()).isEqualTo("CAUTION");assertThat(first.path("summary").path("spans").path(0).path("quote").asText()).isEqualTo("처음");
        var typos=findings(report,"?branch=TYPO");assertThat(typos.path("totalElements").asLong()).isEqualTo(2);
        assertThat(typos.path("content")).extracting(t->t.path("outcome").asText()).containsExactlyInAnyOrder("SPACING","SPELLING");
        assertThat(typos.path("content").path(0).path("summary").path("replacement").isTextual()).isTrue();assertThat(typos.path("content").path(0).path("checkRevision").isNull()).isTrue();
        assertThat(findings(report,"?outcome=SPELLING").path("totalElements").asLong()).isEqualTo(1);assertThat(findings(report,"?outcome=CONFLICT&branch=SETTING").path("totalElements").asLong()).isEqualTo(2);
        judge(report,findings(report,"?branch=SETTING").path("content").path(0),"NEEDS_FIX");
        assertThat(findings(report,"?judgment=NEEDS_FIX").path("totalElements").asLong()).isEqualTo(1);assertThat(findings(report,"?judgment=PENDING").path("totalElements").asLong()).isEqualTo(3);
        assertThat(findings(report,"?judgment=PENDING&branch=SETTING").path("totalElements").asLong()).isEqualTo(1);
        for(String bad:List.of("?size=0","?size=101","?page=-1"))assertThat(read("/"+report+"/findings"+bad,token,400).path("code").asText()).as(bad).isEqualTo("REQUEST_VALIDATION_FAILED");
        for(String bad:List.of("?branch=UNKNOWN","?judgment=APPROVED"))assertThat(read("/"+report+"/findings"+bad,token,400).path("code").asText()).as(bad).isEqualTo("REQUEST_INVALID_ARGUMENT");
        var setting=detail(report,first);
        assertThat(setting.path("evidence").size()).isEqualTo(3);assertThat(setting.path("evidence")).extracting(e->e.path("sourceRef").asText()).contains("T1","R1");
        assertThat(texts(setting.path("allowedJudgments"))).containsExactly("PENDING","NEEDS_FIX","NOT_CONFLICT");
        assertThat(setting.path("payload").path("reason").asText()).isEqualTo("합성 충돌 판정");assertThat(setting.path("payload").path("completion").path("required_reads").has("receipt_refs")).isFalse();
        assertThat(setting.toString()).doesNotContain("evidence_bundle","source_catalogue","lookup_intents","receipt_refs","checkInputDigest","leaseToken");
        var typo=detail(report,findings(report,"?outcome=SPELLING").path("content").path(0));
        assertThat(typo.path("evidence")).extracting(e->e.path("quote").asText()).containsExactly("원고");assertThat(texts(typo.path("allowedJudgments"))).containsExactly("PENDING","NEEDS_FIX","KEEP_ORIGINAL");
        assertThat(typo.path("priorRevisions").size()).isZero();assertThat(typo.path("changed").asBoolean()).isFalse();noExtraction();
    }
}
