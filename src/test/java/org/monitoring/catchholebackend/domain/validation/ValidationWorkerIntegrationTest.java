package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** All numeric limits here are synthetic fixtures, not production defaults. */
@SpringBootTest(properties="spring.config.import=") @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@DisplayName("검수 전용 API와 불변 입력·독립 분기·원장")
class ValidationWorkerIntegrationTest extends ValidationWorkerFixture {
    @Test @DisplayName("실제 전용 Job과 서버 소유 scope를 선점하고 추출은 만들지 않는다") void claimCreatesDedicatedOwnedJobAndNoExtraction() throws Exception {
        var report=start();var job=claim();
        assertThat(job.path("jobId").isTextual()).isTrue();assertThat(job.path("scope").path("reportId")).isEqualTo(report.path("reportId"));
        assertThat(job.path("memberId").asLong()).isEqualTo(member.getId());assertThat(job.path("workId").asText()).isEqualTo(work.getId().toString());
        assertThat(job.path("models").path("RELATED_SELECTION").path("reasoningEffort").asText()).isEqualTo("high");
        assertThat(claim().isNull()).isTrue();noExtraction();
    }
    @Test @DisplayName("전용 Worker 경로는 내부 key 인증을 요구한다") void workerRoutesRequireInternalKey() throws Exception {
        mvc.perform(post(WORKER+"/claim").contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isUnauthorized());noExtraction();
    }
    @Test @DisplayName("다른 lease와 report scope의 쓰기를 거절한다") void wrongLeaseAndImmutableScopeRejected() throws Exception {
        start();var job=claim();var wrong=job.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)wrong).put("leaseToken",UUID.randomUUID().toString());
        assertThat(internal(path(job,"/heartbeat"),wrong,Map.of("scope",scope(job)),409).path("code").asText()).isEqualTo("VALIDATION_LEASE_CONFLICT");
        var scope=scope(job);scope.put("reportId",UUID.randomUUID());
        internal(path(job,"/heartbeat"),job,Map.of("scope",scope),409);noExtraction();
    }
    @Test @DisplayName("실행 Job의 자료 읽기는 unleased 우회를 허용하지 않는다") void runningJobCannotUseUnleasedDataReader() throws Exception {
        var report=start();var job=claim();
        var request=Map.of("expectedBaselineHash",report.path("baselineHash").asText(),"expectedTargetHash",target.getContentHash(),"operation","INPUT","offset",0,"limit",100);
        mvc.perform(post("/api/internal/v1/works/"+work.getId()+"/validation-reports/"+report.path("reportId").asText()+"/data")
                .header("X-Internal-Api-Key",KEY).header("X-Validation-Member-Id",member.getId()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().isConflict());noExtraction();
    }
    @Test @DisplayName("만료 lease는 연장이나 새 호출을 예약하지 못한다") void expiredLeaseCannotHeartbeatOrReserve() throws Exception {
        start();var job=claim();em.createNativeQuery("update validation_jobs set lease_expires_at=CURRENT_TIMESTAMP - interval '1' second").executeUpdate();em.clear();
        internal(path(job,"/heartbeat"),job,Map.of("scope",scope(job)),409);
        internal(path(job,"/usage/reserve"),job,Map.of("scope",scope(job),"requestId",UUID.randomUUID(),"role","PLANNER","model","gpt-6.1-terra","reservedTokens",100),409);noExtraction();
    }
    @Test @DisplayName("동일 단계의 재전송은 원 receipt와 의미 version을 유지한다") void savedStagesHaveExactReplayAndStableCheckpoint() throws Exception {
        source();start();var job=claim();var input=publication(job,"SETTING","PLANNER","TSEG1",Map.of("questions",List.of(),"segments",List.of(Map.of("segment_ref","TSEG1","status","NO_CHECK","reason","합성 완료")),"rules",List.of()));
        var first=internal(path(job,"/plans"),job,input,200);var replay=internal(path(job,"/plans"),job,input,200);assertThat(replay).isEqualTo(first);
        var before=get(UUID.fromString(job.path("scope").path("reportId").asText()));input.put("idempotencyKey","same-stage-new-receipt");internal(path(job,"/plans"),job,input,200);
        assertThat(get(UUID.fromString(job.path("scope").path("reportId").asText())).path("reportVersion")).isEqualTo(before.path("reportVersion"));
        input.put("payload",Map.of("changed",true));internal(path(job,"/plans"),job,input,409);
        var checkpoint=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(WORKER+path(job,"/checkpoint")).header("X-Internal-Api-Key",KEY).header("X-Worker-Lease-Token",job.path("leaseToken").asText())).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(checkpoint.getResponse().getContentAsString()).path("data").path("stages").size()).isEqualTo(1);noExtraction();
    }
    @Test @DisplayName("finding 0개만으로 처리 누락을 완료시키지 않는다") void emptyFindingsDoNotCompleteMissingCoverage() throws Exception {
        start();var job=claim();var done=internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","stop","failureCode","MODEL_CALL_FAILED"),200);
        assertThat(done.path("status").asText()).isEqualTo("FAILED");noExtraction();
    }
    @Test @DisplayName("같은 입력 재시도는 report를 보존하고 실행 세대만 바꾼다") void retrySameInputPreservesReportAndAdvancesGeneration() throws Exception {
        var report=start();var job=claim();internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","stop","failureCode","MODEL_CALL_FAILED"),200);
        var current=get(UUID.fromString(report.path("reportId").asText()));
        var retried=postOk("/"+report.path("reportId").asText()+"/retries",Map.of("expectedReportVersion",current.path("reportVersion").asLong(),"idempotencyKey","retry-same"));
        assertThat(retried.path("reportId")).isEqualTo(report.path("reportId"));var next=claim();assertThat(next.path("scope").path("generation").asLong()).isEqualTo(job.path("scope").path("generation").asLong()+1);
        internal(path(job,"/heartbeat"),job,Map.of("scope",scope(job)),409);noExtraction();
    }
    @Test @DisplayName("원문 변경은 checkpoint를 지우고 늦은 게시를 거절한다") void sourceInvalidationErasesCheckpointAndRejectsLatePublish() throws Exception {
        source();var report=start();var job=claim();
        var input=publication(job,"SETTING","PLANNER","TSEG1",Map.of("questions",List.of(),"segments",List.of(Map.of("segment_ref","TSEG1","status","NO_CHECK","reason","합성 완료")),"rules",List.of()));
        internal(path(job,"/plans"),job,input,200);replace("수정 원고");internal(path(job,"/plans"),job,input,409);
        assertThat(em.createNativeQuery("select count(*) from validation_stages").getSingleResult()).isEqualTo(0L);noExtraction();
    }
    @Test @DisplayName("양쪽 필수 처리가 저장되어야 정상 0건을 완료한다") void onlyBothDurableBranchesCompleteAndEmptyFindingsStayZero() throws Exception {
        source();start();var job=claim();prePlan(job,List.of());savePlan(job,"MERGED_PLAN","PLAN",Map.of("questions",List.of()));
        var settingOnly=internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","stop-setting"),200);assertThat(settingOnly.path("status").asText()).isEqualTo("PARTIAL");
        var report=get(UUID.fromString(job.path("scope").path("reportId").asText()));postOk("/"+report.path("reportId").asText()+"/retries",Map.of("expectedReportVersion",report.path("reportVersion").asLong(),"idempotencyKey","both-retry"));
        var next=claim();assertThat(next.path("checkpoint").path("stages").size()).isEqualTo(4);
        internal(path(next,"/units"),next,unit(next,"TYPO","TSEG1","COMPLETED",typoEmpty()),200);
        var finish=Map.of("scope",scope(next),"idempotencyKey","both-complete");var result=internal(path(next,"/complete"),next,finish,200);
        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");assertThat(result.path("completedChecks").asInt()).isZero();assertThat(result.path("findings").size()).isZero();
        // GH312 S2: 정상 0건 완료는 같은 트랜잭션에서 검토 종료 receipt와 release Job 하나를 만들고 재전송은 같은 receipt다.
        assertThat(result.path("extractionJobId").asText()).isEqualTo(releaseOnly().toString());
        assertThat(internal(path(next,"/complete"),next,finish,200)).isEqualTo(result);releaseOnly();
    }
    @Test @DisplayName("정상 질문도 안정 ID·완료 bundle·새 revision을 보존한다") void normalNoConflictDecisionHasStableServerCheckAndRevisionChangesAreAtomic() throws Exception {
        source();start();var job=claim();
        var span=Map.of("source_ref","T1","start",0,"end",2,"quote","처음");
        var citation=Map.of("sourceRef","T1","sourceHash",target.getContentHash(),"sourceVersion",target.getContentS3Version(),"start",0,"end",2,"quote","처음");
        var draft=draft("local-1",List.of(span));
        var planner=publication(job,"SETTING","PLANNER","TSEG1",batch("TSEG1",List.of(draft)));planner.put("citations",List.of(citation));internal(path(job,"/plans"),job,planner,200);
        savePlan(job,"SUBJECT_RESOLUTION","TSEG1",Map.of("mentions",List.of()));savePlan(job,"COVERAGE","TSEG1",batch("TSEG1",List.of()));
        var plan=publication(job,"SETTING","MERGED_PLAN","PLAN",Map.of("questions",List.of(Map.of("localRef","Q1","originalRefs",List.of("PLANNER:TSEG1:local-1"),"state",Map.of("draft",draft)))));plan.put("citations",List.of(citation));
        var saved=internal(path(job,"/plans"),job,plan,200);var check=saved.path("checks").path(0);assertThat(check.path("checkId").isTextual()).isTrue();
        var assessment=Map.of("reason","실제 확인","evidence_refs",List.of("T1"),"receipt_refs",List.of("L1","L2"));
        var decision=new LinkedHashMap<String,Object>(Map.of("action","DECIDED","outcome","NO_CONFLICT","reason","같은 문맥에서 정상","target_spans",List.of(span),"completion",Map.of("comparison_context",assessment,"changes_exceptions",assessment,"no_pending_clues",assessment,"required_reads",assessment),"follow_ups",List.of(),"affected_question_refs",List.of()));
        var missingBundle=unit(job,"SETTING","Q1","NO_CONFLICT",decision);missingBundle.put("idempotencyKey","missing-bundle");missingBundle.put("checkId",check.path("checkId").asText());missingBundle.put("expectedCheckRevision",1);missingBundle.put("checkInputDigest",check.path("checkInputDigest").asText());missingBundle.put("citations",List.of(citation));
        internal(path(job,"/units"),job,missingBundle,400);
        decision.put("basis","COMPATIBLE");decision.put("evidence_refs",List.of("T1"));decision.put("disagreements",List.of());decision.put("evidence_bundle",evidenceBundle(draft));
        var contextCitation=Map.of("sourceRef","T1","sourceHash",target.getContentHash(),"sourceVersion",target.getContentS3Version(),"start",0,"end",8,"quote","처음 합성 원고");
        var publish=unit(job,"SETTING","Q1","NO_CONFLICT",decision);publish.put("checkId",check.path("checkId").asText());publish.put("expectedCheckRevision",1);publish.put("checkInputDigest",check.path("checkInputDigest").asText());publish.put("citations",List.of(citation,contextCitation));
        internal(path(job,"/units"),job,publish,200);
        var progress=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base()+"/"+job.path("scope").path("reportId").asText()+"/results").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(progress).doesNotContain("evidence_bundle","lookup_intents","leaseToken","checkInputDigest","catalogue");noExtraction();
        internal(path(job,"/checkpoint"),job,Map.of("scope",scope(job),"idempotencyKey","before-revision-cp","branch","SETTING","expectedVersion",0,"payload",Map.of("phase","JUDGING","savedReceipt","authenticated-original"),"citations",List.of()),200);
        var changes=new ArrayList<Object>();changes.add(Map.of("checkId",check.path("checkId").asText(),"expectedRevision",1,"expectedInputDigest",check.path("checkInputDigest").asText(),"state",Map.of("draft",draft,"lookup_obligations",List.of(lookup("NEW-1","NEW_CLUE","TARGET_CONTEXT")),"new_clue","같은 속성의 새 단서")));
        var revised=internal(path(job,"/revisions"),job,Map.of("scope",scope(job),"idempotencyKey","follow-up","reason","NEW_CLUE","changes",changes,"citations",List.of(citation)),200);
        assertThat(revised.path("checks").path(0).path("revision").asLong()).isEqualTo(2);assertThat(revised.path("checks").path(0).path("outcome").isNull()).isTrue();
        var resumed=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(WORKER+path(job,"/checkpoint")).header("X-Internal-Api-Key",KEY).header("X-Worker-Lease-Token",job.path("leaseToken").asText())).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(resumed.getResponse().getContentAsString()).at("/data/branches/SETTING/payload/savedReceipt").asText()).isEqualTo("authenticated-original");
        internal(path(job,"/units"),job,publish,409);
        var old=new LinkedHashMap<>(publish);old.put("idempotencyKey","late-old-revision");internal(path(job,"/units"),job,old,409);
        assertThat(em.createNativeQuery("select count(*) from validation_check_history").getSingleResult()).isEqualTo(1L);
    }
    @Test @DisplayName("두 분기의 checkpoint CAS와 heartbeat의 공개 version을 분리한다") void independentBranchCheckpointsHaveSeparateCasAndNoSharedReportVersion() throws Exception {
        source();var report=start();var job=claim();
        for(String branch:List.of("SETTING","TYPO"))internal(path(job,"/checkpoint"),job,Map.of("scope",scope(job),"idempotencyKey","checkpoint-"+branch,"branch",branch,"expectedVersion",0,"payload",Map.of("validated_stage","saved"),"citations",List.of()),200);
        internal(path(job,"/checkpoint"),job,Map.of("scope",scope(job),"idempotencyKey","stale-cp","branch","SETTING","expectedVersion",0,"payload",Map.of(),"citations",List.of()),409);
        var before=get(UUID.fromString(report.path("reportId").asText()));internal(path(job,"/heartbeat"),job,Map.of("scope",scope(job)),200);
        assertThat(get(UUID.fromString(report.path("reportId").asText())).path("reportVersion")).isEqualTo(before.path("reportVersion"));noExtraction();
    }
    @Test @DisplayName("실제 검수 원장은 UNKNOWN·실패·늦은 사용량을 정확히 정산한다") void realValidationLedgerSettlesFailureUnknownAndLateLeaseUsageExactly() throws Exception {
        start();var job=claim();UUID request=UUID.randomUUID();
        var reserve=Map.of("scope",scope(job),"requestId",request,"role","RELATED_SELECTION","model","gpt-6.1-terra","reservedTokens",100);
        var first=internal(path(job,"/usage/reserve"),job,reserve,200);assertThat(internal(path(job,"/usage/reserve"),job,reserve,200)).isEqualTo(first);
        var conflict=new LinkedHashMap<String,Object>(reserve);conflict.put("reservedTokens",101);internal(path(job,"/usage/reserve"),job,conflict,409);
        assertThat(em.createNativeQuery("select count(*) from ai_token_usages where validation_job_id is not null and analysis_job_id is null and chat_run_id is null").getSingleResult()).isEqualTo(1L);
        var unknown=Map.of("scope",scope(job),"kind","UNKNOWN");internal(path(job,"/usage/"+request),job,unknown,200);
        replace("수정 원고");
        var settlement=Map.of("scope",scope(job),"kind","SETTLE","inputTokens",25,"cachedInputTokens",5,"outputTokens",7,"outcome","FAILURE");
        assertThat(internal(path(job,"/usage/"+request),job,settlement,200).path("status").asText()).isEqualTo("SETTLED");internal(path(job,"/usage/"+request),job,settlement,200);
        assertThat(((java.math.BigDecimal)em.createNativeQuery("select used_tokens from ai_token_accounts where member_id=:m").setParameter("m",member.getId()).getSingleResult())).isEqualByComparingTo("32");
        var other=new LinkedHashMap<String,Object>(settlement);other.put("inputTokens",26);internal(path(job,"/usage/"+request),job,other,409);noExtraction();
    }
    @Test @DisplayName("실제 계정 잔액과 고정 모델을 검증하고 무효 실행의 새 예약을 막는다") void quotaAndModelLimitsAreRealAndNewReservationsStopAfterInvalidation() throws Exception {
        start();var job=claim();var request=new LinkedHashMap<String,Object>(Map.of("scope",scope(job),"requestId",UUID.randomUUID(),"role","PLANNER","model","gpt-6.1-terra","reservedTokens",100));
        request.put("model","gpt-6-luna");internal(path(job,"/usage/reserve"),job,request,400);request.put("model","gpt-6.1-terra");
        em.createNativeQuery("update ai_token_accounts set granted_tokens=0 where member_id=:m").setParameter("m",member.getId()).executeUpdate();em.clear();
        // Force creation, then the genuine account lock is authoritative for every call.
        internal(path(job,"/usage/reserve"),job,request,200);em.flush();em.createNativeQuery("update ai_token_accounts set granted_tokens=used_tokens+reserved_tokens where member_id=:m").setParameter("m",member.getId()).executeUpdate();em.clear();
        request.put("requestId",UUID.randomUUID());internal(path(job,"/usage/reserve"),job,request,409);replace("새 입력");internal(path(job,"/usage/reserve"),job,request,409);noExtraction();
    }
    @Test @DisplayName("준비 계획만 성공한 종료는 FAILED이며 저장 계획은 남긴다") void plannerOnlySuccessIsFailedAndItsSavedPlanRemains() throws Exception {
        source();start();var job=claim();savePlan(job,"PLANNER","TSEG1",batch("TSEG1",List.of()));
        var finish=internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","planner-only","failureCode","MODEL_CALL_FAILED"),200);
        assertThat(finish.path("status").asText()).isEqualTo("FAILED");assertThat(finish.path("coverage").path("completedStages").asInt()).isEqualTo(1);noExtraction();
    }
    void targetText(String text) throws Exception {
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        target.updateContentStorage(target.getContentS3Key(),target.getContentS3Version(),hash);org.springframework.test.util.ReflectionTestUtils.setField(target,"validationUploadEligible",true);em.flush();
        when(storage.getText(anyString())).thenReturn(text);
    }
    @Test @DisplayName("모든 필수 점검이 끝난 무질문 구간은 부분 성공으로 센다") void fullyCoveredNoCheckSegmentCountsBeforeAnotherSegmentFails() throws Exception {
        targetText("가".repeat(105));String bookKey="synthetic/empty-book";String emptyHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(new byte[0]));
        var book=org.monitoring.catchholebackend.domain.upload.entity.UploadFile.create(em.find(org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.class,batchId),org.monitoring.catchholebackend.domain.upload.type.UploadFileRole.SETTING_BOOK,"empty.txt","text/plain","s3://synthetic/empty",0);em.persist(book);
        var revision=org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision.create(book,1,new org.monitoring.catchholebackend.global.storage.StoredTextObject(bookKey,"v1",emptyHash,0),org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind.WORK_START,null);em.persist(revision);book.linkCurrentRevision(revision);em.flush();when(storage.getText(bookKey)).thenReturn("");
        start();var job=claim();prePlan(job,List.of());savePlan(job,"BOOK_SOURCE","R2",Map.of("source_read_status","READY","planned_checks",0));
        var finish=internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","one-no-check","failureCode","MODEL_CALL_FAILED"),200);
        assertThat(finish.path("status").asText()).isEqualTo("PARTIAL");assertThat(finish.path("completedNoCheckSegments").asInt()).isEqualTo(1);noExtraction();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"T1","R1"})
    @DisplayName("실제 원문 인용이 맞아도 현재 회차만의 충돌은 거절하고 과거 출처 충돌은 저장한다")
    void conflictPublicationRequiresActualNonTargetEvidence(String sourceRef) throws Exception {
        source();String priorHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("합성 앞 원고".getBytes(java.nio.charset.StandardCharsets.UTF_8)));prior.updateContentStorage(prior.getContentS3Key(),prior.getContentS3Version(),priorHash);em.flush();start();var job=claim();
        var span=Map.of("source_ref","T1","start",0,"end",2,"quote","처음");var draft=draft("local-1",List.of(span));var citation=Map.of("sourceRef","T1","sourceHash",target.getContentHash(),"sourceVersion",target.getContentS3Version(),"start",0,"end",2,"quote","처음");
        var planner=publication(job,"SETTING","PLANNER","TSEG1",batch("TSEG1",List.of(draft)));planner.put("citations",List.of(citation));internal(path(job,"/plans"),job,planner,200);
        savePlan(job,"SUBJECT_RESOLUTION","TSEG1",Map.of("mentions",List.of()));savePlan(job,"COVERAGE","TSEG1",batch("TSEG1",List.of()));
        var plan=publication(job,"SETTING","MERGED_PLAN","PLAN",Map.of("questions",List.of(Map.of("localRef","Q1","originalRefs",List.of("PLANNER:TSEG1:local-1"),"state",Map.of("draft",draft)))));plan.put("citations",List.of(citation));var check=internal(path(job,"/plans"),job,plan,200).path("checks").path(0);
        var bundle=evidenceBundle(draft);String quote=sourceRef.equals("T1")?"처음":"합성";bundle.put("evidence",List.of(Map.of("ref","E1","source_ref",sourceRef,"source_kind","EPISODE","start",0,"end",2,"quote",quote)));
        var assessment=Map.of("reason","실제 고정 자료 확인","evidence_refs",List.of("T1","E1"),"receipt_refs",List.of("L1","L2"));
        var decision=new LinkedHashMap<String,Object>();decision.put("action","DECIDED");decision.put("outcome","CONFLICT");decision.put("basis","DIRECT_CONTRADICTION");decision.put("reason","합성 충돌 판정");decision.put("impact","CAUTION");decision.put("impact_reason","비교값 차이");decision.put("target_spans",List.of(span));decision.put("evidence_refs",List.of("T1","E1"));decision.put("completion",Map.of("comparison_context",assessment,"changes_exceptions",assessment,"no_pending_clues",assessment,"required_reads",assessment));decision.put("follow_ups",List.of());decision.put("affected_question_refs",List.of());decision.put("disagreements",List.of());decision.put("evidence_bundle",bundle);
        var citations=new ArrayList<Object>();citations.add(citation);citations.add(Map.of("sourceRef","T1","sourceHash",target.getContentHash(),"sourceVersion",target.getContentS3Version(),"start",0,"end",8,"quote","처음 합성 원고"));if(sourceRef.equals("R1"))citations.add(Map.of("sourceRef","R1","sourceHash",priorHash,"sourceVersion",prior.getContentS3Version(),"start",0,"end",2,"quote",quote));
        var command=unit(job,"SETTING","Q1","CONFLICT",decision);command.put("checkId",check.path("checkId").asText());command.put("expectedCheckRevision",1);command.put("checkInputDigest",check.path("checkInputDigest").asText());command.put("citations",citations);
        var before=get(UUID.fromString(job.path("scope").path("reportId").asText()));var result=internal(path(job,"/units"),job,command,sourceRef.equals("T1")?400:200);
        assertThat(em.createNativeQuery("select count(*) from validation_findings").getSingleResult()).isEqualTo(sourceRef.equals("T1")?0L:1L);
        if(sourceRef.equals("T1")) {assertThat(result.path("code").asText()).isEqualTo("VALIDATION_RESULT_INVALID");assertThat(get(UUID.fromString(job.path("scope").path("reportId").asText())).path("reportVersion")).isEqualTo(before.path("reportVersion"));}else assertThat(result.path("outcome").asText()).isEqualTo("CONFLICT");noExtraction();
    }
    @Test @DisplayName("반복 비BMP 인용은 정확한 code point 위치만 허용한다") void unicodeCodePointQuotesKeepRepeatedPositionsAndRejectUtf16Offsets() throws Exception {
        targetText("😀가😀가");start();var job=claim();
        var citation=Map.of("sourceRef","T1","sourceHash",target.getContentHash(),"sourceVersion",target.getContentS3Version(),"start",2,"end",3,"quote","😀");
        var body=new LinkedHashMap<String,Object>(Map.of("scope",scope(job),"idempotencyKey","unicode","branch","SETTING","expectedVersion",0,
                "payload",Map.of("evidence",List.of(Map.of("source_ref","T1","start",2,"end",3,"quote","😀"))),"citations",List.of(citation)));
        internal(path(job,"/checkpoint"),job,body,200);
        var bad=new LinkedHashMap<String,Object>(citation);bad.put("start",1);bad.put("end",2);
        body.put("idempotencyKey","utf16-offset");body.put("expectedVersion",1);body.put("citations",List.of(bad));internal(path(job,"/checkpoint"),job,body,400);noExtraction();
    }
    @Test @DisplayName("확실한 미호출 해제와 사용량 미상의 예산 의미를 구분한다") void knownNoCallReleaseReturnsRoleCapacityButUnknownDoesNot() throws Exception {
        start();var job=claim();UUID request=UUID.randomUUID();
        internal(path(job,"/usage/reserve"),job,Map.of("scope",scope(job),"requestId",request,"role","PLANNER","model","gpt-6.1-terra","reservedTokens",100),200);
        assertThat(internal(path(job,"/usage/"+request),job,Map.of("scope",scope(job),"kind","RELEASE"),200).path("status").asText()).isEqualTo("RELEASED");
        for(int i=0;i<20;i++) {
            UUID call=UUID.randomUUID();internal(path(job,"/usage/reserve"),job,Map.of("scope",scope(job),"requestId",call,"role","PLANNER","model","gpt-6.1-terra","reservedTokens",100),200);
            internal(path(job,"/usage/"+call),job,Map.of("scope",scope(job),"kind","UNKNOWN"),200);
        }
        internal(path(job,"/usage/reserve"),job,Map.of("scope",scope(job),"requestId",UUID.randomUUID(),"role","PLANNER","model","gpt-6.1-terra","reservedTokens",100),409);noExtraction();
    }
    @Test @DisplayName("소진 재시도는 거절하고 명시 cap 변경만 기존 누계에서 재개한다") void retryRejectsExhaustedRequiredRoleInsteadOfRequeueLoop() throws Exception {
        var report=start();var job=claim();
        for(int i=0;i<20;i++) {UUID call=UUID.randomUUID();internal(path(job,"/usage/reserve"),job,Map.of("scope",scope(job),"requestId",call,"role","PLANNER","model","gpt-6.1-terra","reservedTokens",100),200);internal(path(job,"/usage/"+call),job,Map.of("scope",scope(job),"kind","UNKNOWN"),200);}
        internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","stop-capped","failureCode","BUDGET_EXHAUSTED"),200);
        var current=get(UUID.fromString(report.path("reportId").asText()));
        conflict("/"+report.path("reportId").asText()+"/retries",Map.of("expectedReportVersion",current.path("reportVersion").asLong(),"idempotencyKey","no-loop"),"VALIDATION_EXECUTION_LIMIT_EXCEEDED");
        var raised=new java.util.EnumMap<org.monitoring.catchholebackend.domain.validation.type.ValidationRole,org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.RoleBudget>(executionProperties.roles());
        raised.put(org.monitoring.catchholebackend.domain.validation.type.ValidationRole.PLANNER,new org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.RoleBudget(21,22000));org.mockito.Mockito.when(executionProperties.roles()).thenReturn(raised);
        var retry=Map.of("expectedReportVersion",current.path("reportVersion").asLong(),"idempotencyKey","raised-budget");var receipt=postOk("/"+report.path("reportId").asText()+"/retries",retry);var next=claim();
        assertThat(next.path("budgetRevision").asInt()).isEqualTo(2);assertThat(next.path("budgets").path("roles").path("PLANNER").path("maxCalls").asInt()).isEqualTo(21);assertThat(next.path("usage").size()).isEqualTo(20);
        assertThat(postOk("/"+report.path("reportId").asText()+"/retries",retry)).isEqualTo(receipt);
        internal(path(next,"/usage/reserve"),next,Map.of("scope",scope(next),"requestId",UUID.randomUUID(),"role","PLANNER","model","gpt-6.1-terra","reservedTokens",100),200);
        internal(path(next,"/usage/reserve"),next,Map.of("scope",scope(next),"requestId",UUID.randomUUID(),"role","PLANNER","model","gpt-6.1-terra","reservedTokens",100),409);noExtraction();
    }
    @Test @DisplayName("서버 발급 CL의 누락은 독립 보완 완료가 아니다") void coverageCannotOmitServerIssuedMechanicalClues() throws Exception {
        targetText("😀20\u00a0살 월요일");start();var job=claim();savePlan(job,"PLANNER","TSEG1",batch("TSEG1",List.of()));
        savePlan(job,"SUBJECT_RESOLUTION","TSEG1",Map.of("mentions",List.of()));
        internal(path(job,"/plans"),job,publication(job,"SETTING","COVERAGE","TSEG1",batch("TSEG1",List.of())),400);
        var body=new LinkedHashMap<String,Object>(batch("TSEG1",List.of()));body.put("clue_checks",List.of(Map.of("entry_ref","CL1","status","CHECKED","reason","수량 확인"),Map.of("entry_ref","CL2","status","NOT_APPLICABLE","reason","요일 문맥 확인")));
        savePlan(job,"COVERAGE","TSEG1",body);noExtraction();
    }
    @Test @DisplayName("grid v2는 page 선별 대신 SUBJECT_RESOLUTION 뒤 원고에 이름이 나온 주체의 모든 항목을 COVERAGE 규칙으로 요구한다")
    void coverageRequiresSubjectResolutionAndEveryEntryOfMentionedSubjects() throws Exception {
        targetText("합성 인물이 웃었다");start();var job=claim();savePlan(job,"PLANNER","TSEG1",batch("TSEG1",List.of()));
        internal(path(job,"/plans"),job,publication(job,"SETTING","RELATED_SELECTION","TSEG1:PAGE1",Map.of("entries",List.of())),400);
        internal(path(job,"/plans"),job,publication(job,"SETTING","COVERAGE","TSEG1",batch("TSEG1",List.of())),400);
        internal(path(job,"/plans"),job,publication(job,"SETTING","SUBJECT_RESOLUTION","TSEG1",Map.of("mentions",List.of(Map.of("mention","그","subject_ref","S99","reason","없는 주체")))),400);
        savePlan(job,"SUBJECT_RESOLUTION","TSEG1",Map.of("mentions",List.of()));
        var entries=stored(get(UUID.fromString(job.path("scope").path("reportId").asText()))).getBaselineEntries();
        var subjects=org.monitoring.catchholebackend.domain.validation.mapper.ValidationDataMapper.toSubjectRefs(entries);var mentioned=new HashSet<String>();
        for(int i=0;i<entries.size();i++) {var state=entries.get(i).path("state");if("합성 인물".equals(state.path(state.hasNonNull("name")?"name":"subjectName").asText()))mentioned.add(subjects.get("B"+(i+1)));}
        var rules=new ArrayList<Object>();subjects.forEach((entry,subject)->{if(mentioned.contains(subject))rules.add(Map.of("entry_ref",entry,"status","CHECKED","reason","합성 규칙 확인"));});
        assertThat(rules).isNotEmpty();assertThat(rules.size()).isLessThan(entries.size());
        internal(path(job,"/plans"),job,publication(job,"SETTING","COVERAGE","TSEG1",batch("TSEG1",List.of())),400);
        var coverage=new LinkedHashMap<String,Object>(batch("TSEG1",List.of()));coverage.put("rules",rules);savePlan(job,"COVERAGE","TSEG1",coverage);noExtraction();
    }
    @Test @DisplayName("새 공개 재시도만 최신 회차를 검사하고 원 receipt와 기존 lease는 보존한다") void publicRetryRequiresLatestEpisodeButOriginalReceiptAndLeaseRecoveryDoNot() throws Exception {
        var report=start();var job=claim();internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","old-stop","failureCode","MODEL_CALL_FAILED"),200);
        var later=org.monitoring.catchholebackend.domain.episode.entity.Episode.create(work,null,102,"후속","synthetic/102","v1","c".repeat(64),1);em.persist(later);em.flush();
        var current=get(UUID.fromString(report.path("reportId").asText()));var retry=Map.of("expectedReportVersion",current.path("reportVersion").asLong(),"idempotencyKey","latest-only");
        mvc.perform(post(base()+"/"+report.path("reportId").asText()+"/retries").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(retry)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_TARGET_INVALID"));
        assertThat(em.createNativeQuery("select generation from validation_jobs").getSingleResult()).isEqualTo(1L);assertThat(em.createNativeQuery("select status from validation_jobs").getSingleResult()).isEqualTo("FAILED");assertThat(get(UUID.fromString(report.path("reportId").asText())).path("reportVersion")).isEqualTo(current.path("reportVersion"));later.archive();em.flush();
        var receipt=postOk("/"+report.path("reportId").asText()+"/retries",retry);
        var nextLater=org.monitoring.catchholebackend.domain.episode.entity.Episode.create(work,null,102,"다시 후속","synthetic/102-new","v1","c".repeat(64),1);em.persist(nextLater);em.flush();
        assertThat(postOk("/"+report.path("reportId").asText()+"/retries",retry)).isEqualTo(receipt);assertThat(claim().path("scope").path("reportId")).isEqualTo(report.path("reportId"));noExtraction();
    }
    @Test @DisplayName("과거 대기는 명시 접수로만 queue하고 임베딩은 명시 예산으로만 추가한다") void disabledWaitingReportQueuesOnlyOnExplicitRecoveryAndEmbeddingNeedsExplicitRetryBudget() throws Exception {
        org.mockito.Mockito.when(executionProperties.enabled()).thenReturn(false);var report=start();assertThat(report.path("validationJobId").isNull()).isTrue();internal("/claim",null,Map.of("contractVersion",1,"workerId","synthetic","capabilities",List.of("VALIDATION_EXECUTION_V1")),409);
        org.mockito.Mockito.when(executionProperties.enabled()).thenReturn(true);assertThat(em.createNativeQuery("select count(*) from validation_jobs").getSingleResult()).isEqualTo(0L);
        var recovered=start();assertThat(recovered.path("reportId")).isEqualTo(report.path("reportId"));var job=claim();
        var request=Map.of("scope",scope(job),"requestId",UUID.randomUUID(),"role","QUERY_EMBEDDING","model","text-embedding-3-small","reservedTokens",20);
        internal(path(job,"/usage/reserve"),job,request,400);internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","optional-stop","failureCode","VECTOR_NOT_READY"),200);
        var roles=new java.util.EnumMap<org.monitoring.catchholebackend.domain.validation.type.ValidationRole,org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.RoleBudget>(executionProperties.roles());roles.put(org.monitoring.catchholebackend.domain.validation.type.ValidationRole.QUERY_EMBEDDING,new org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.RoleBudget(2,1000));
        org.mockito.Mockito.when(executionProperties.roles()).thenReturn(roles);org.mockito.Mockito.when(executionProperties.queryEmbeddingModel()).thenReturn("text-embedding-3-small");
        org.mockito.Mockito.when(executionProperties.setting()).thenReturn(new org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.Limits(21,101,61,1001,101,1,0,1,1));
        var current=get(UUID.fromString(report.path("reportId").asText()));postOk("/"+report.path("reportId").asText()+"/retries",Map.of("expectedReportVersion",current.path("reportVersion").asLong(),"idempotencyKey","explicit-embedding"));var next=claim();
        assertThat(next.path("models").path("QUERY_EMBEDDING").path("model").asText()).isEqualTo("text-embedding-3-small");assertThat(next.path("budgets").path("setting").path("segmentChars").asInt()).isEqualTo(100);assertThat(next.path("budgets").path("setting").path("searchLimit").asInt()).isEqualTo(10);assertThat(next.path("budgets").path("setting").path("modelCalls").asInt()).isEqualTo(21);
        var queryInput=Map.of("contractVersion",1,"inputDigest",next.at("/scope/inputDigest").asText(),"queryHash","a".repeat(64),"indexModel","text-embedding-3-small","indexVersion",next.at("/models/QUERY_EMBEDDING/version").asText(),"indexDimensions",1536,"manifestHash",next.path("manifestHash").asText());
        internal(path(next,"/usage/reserve"),next,Map.of("scope",scope(next),"requestId",UUID.randomUUID(),"role","QUERY_EMBEDDING","model","text-embedding-3-small","reservedTokens",20,"queryInput",queryInput),200);noExtraction();
    }
    @Test @DisplayName("다른 회원과 작품은 저장 결과·고정 원문을 읽지 못한다") void anotherMemberOrWorkCannotReadResultsOrScopedSources() throws Exception {
        var report=start();var job=claim();var other=org.monitoring.catchholebackend.domain.member.entity.Member.register("other-"+UUID.randomUUID()+"@example.invalid","synthetic","01098765432","다른 작가");em.persist(other);String otherToken=tokens.generateAccessToken(other);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base()+"/"+report.path("reportId").asText()+"/results").header("Authorization","Bearer "+otherToken)).andExpect(status().isNotFound());
        var otherWork=org.monitoring.catchholebackend.domain.work.entity.Work.create(member,"다른 작품",org.monitoring.catchholebackend.domain.work.type.WorkGenre.FANTASY,null);em.persist(otherWork);em.flush();
        mvc.perform(post("/api/internal/v1/works/"+otherWork.getId()+"/validation-reports/"+report.path("reportId").asText()+"/data").header("X-Internal-Api-Key",KEY).header("X-Validation-Member-Id",member.getId()).header("X-Validation-Job-Id",job.path("jobId").asText()).header("X-Worker-Lease-Token",job.path("leaseToken").asText()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("expectedBaselineHash",report.path("baselineHash").asText(),"expectedTargetHash",target.getContentHash(),"operation","INPUT","offset",0,"limit",100))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("VALIDATION_NOT_FOUND"));noExtraction();
    }
    @Test @DisplayName("유효 seed는 같은 report와 기준 revision에서 준비를 복구한다") void sameInputRetryMaterializesVerifiedSavedSeedWithoutNewBaseline() throws Exception {
        org.mockito.Mockito.when(executionProperties.enabled()).thenReturn(false);var original=start();var r=stored(original);String hash=r.getBaselineHash();r.hold("SYNTHETIC_PREPARATION_INTERRUPTED");em.flush();
        org.mockito.Mockito.when(executionProperties.enabled()).thenReturn(true);var held=get(r.getId());
        var retried=postOk("/"+r.getId()+"/retries",Map.of("expectedReportVersion",held.path("reportVersion").asLong(),"idempotencyKey","restore-valid-seed"));
        assertThat(retried.path("reportId")).isEqualTo(original.path("reportId"));assertThat(retried.path("baselineRevision").asLong()).isEqualTo(1);assertThat(retried.path("baselineHash").asText()).isEqualTo(hash);assertThat(retried.path("baselineStatus").asText()).isEqualTo("READY");assertThat(retried.path("validationJobId").isTextual()).isTrue();assertThat(claim().path("scope").path("reportId")).isEqualTo(original.path("reportId"));noExtraction();
    }
    @Test @DisplayName("seed 없음과 원문 파기는 같은 입력 재시도로 되살리지 않는다") void seedlessOrPurgedPreparationCannotBeRevivedBySameInputRetry() throws Exception {
        org.mockito.Mockito.when(executionProperties.enabled()).thenReturn(false);var original=start();var r=stored(original);r.hold("SYNTHETIC_PREPARATION_INTERRUPTED");
        org.springframework.test.util.ReflectionTestUtils.setField(r,"baselineSeed",null);org.springframework.test.util.ReflectionTestUtils.setField(r,"baselineHash",null);org.springframework.test.util.ReflectionTestUtils.setField(r,"baselineFingerprint",null);em.flush();
        org.mockito.Mockito.when(executionProperties.enabled()).thenReturn(true);var held=get(r.getId());conflict("/"+r.getId()+"/retries",Map.of("expectedReportVersion",held.path("reportVersion").asLong(),"idempotencyKey","no-seed"),"VALIDATION_SOURCE_VERSION_CONFLICT");
        replace("원문 파기");var purged=get(r.getId());conflict("/"+r.getId()+"/retries",Map.of("expectedReportVersion",purged.path("reportVersion").asLong(),"idempotencyKey","purged-seed"),"VALIDATION_SOURCE_VERSION_CONFLICT");assertThat(em.createNativeQuery("select count(*) from validation_jobs").getSingleResult()).isEqualTo(0L);assertThat(r.getBaselineSeed()).isNull();noExtraction();
    }
    @Test @DisplayName("설정집 읽기 실패는 독립 오탈자와 초기 checkpoint 조회를 막지 않는다") void unreadableBookDoesNotBlockTargetTypoGridOrCheckpointBootstrap() throws Exception {
        source();String bookKey="synthetic/unreadable-book",bookText="고정 설정집";String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bookText.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var book=org.monitoring.catchholebackend.domain.upload.entity.UploadFile.create(em.find(org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.class,batchId),org.monitoring.catchholebackend.domain.upload.type.UploadFileRole.SETTING_BOOK,"book.txt","text/plain","s3://synthetic/book",30);em.persist(book);
        var revision=org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision.create(book,1,new org.monitoring.catchholebackend.global.storage.StoredTextObject(bookKey,"v1",hash,bookText.length()),org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind.WORK_START,null);em.persist(revision);book.linkCurrentRevision(revision);em.flush();org.mockito.Mockito.when(storage.getText(bookKey)).thenThrow(new IllegalStateException("synthetic unavailable"));
        start();var job=claim();mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(WORKER+path(job,"/checkpoint")).header("X-Internal-Api-Key",KEY).header("X-Worker-Lease-Token",job.path("leaseToken").asText())).andExpect(status().isOk()).andExpect(jsonPath("$.data.gridReady").value(false));
        internal(path(job,"/plans"),job,publication(job,"SETTING","PLANNER","TSEG1",batch("TSEG1",List.of())),200);
        internal(path(job,"/checkpoint"),job,Map.of("scope",scope(job),"idempotencyKey","partial-setting-checkpoint","branch","SETTING","expectedVersion",0,"payload",Map.of("saved_stage_refs",List.of("PLANNER:TSEG1")),"citations",List.of()),200);
        internal(path(job,"/plans"),job,publication(job,"SETTING","BOOK_SOURCE","R2",Map.of("source_read_status","READY","planned_checks",1)),409);
        internal(path(job,"/plans"),job,publication(job,"SETTING","MERGED_PLAN","PLAN",Map.of("questions",List.of())),400);
        internal(path(job,"/units"),job,unit(job,"TYPO","TSEG1","COMPLETED",typoEmpty()),200);
        var finish=internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","independent-book","failureCode","SOURCE_UNREADABLE"),200);
        assertThat(finish.path("status").asText()).isEqualTo("PARTIAL");assertThat(finish.path("typoStatus").asText()).isEqualTo("COMPLETED");assertThat(finish.path("settingStatus").asText()).isEqualTo("FAILED");assertThat(finish.path("coverage").path("gridReady").asBoolean()).isFalse();assertThat(finish.path("completedNoCheckSegments").asInt()).isZero();org.mockito.Mockito.verify(storage,org.mockito.Mockito.times(1)).getText(bookKey);noExtraction();
    }
    @Test @DisplayName("TYPO의 최종 검증을 계획 endpoint로 우회할 수 없다") void typoCoverageCannotBypassUnitValidationThroughPlanRoute() throws Exception {
        source();var report=start();var job=claim();var before=get(UUID.fromString(report.path("reportId").asText()));
        internal(path(job,"/plans"),job,publication(job,"TYPO","TYPO_SEGMENT","TSEG1",Map.of("segments",List.of(Map.of("segment_ref","TSEG1","status","NO_CHECK","reason","제안 검증 우회 시도")))),400);
        assertThat(em.createNativeQuery("select count(*) from validation_stages").getSingleResult()).isEqualTo(0L);assertThat(em.createNativeQuery("select count(*) from validation_publications").getSingleResult()).isEqualTo(0L);assertThat(get(UUID.fromString(report.path("reportId").asText())).path("reportVersion")).isEqualTo(before.path("reportVersion"));noExtraction();
    }
    @Test @DisplayName("embedding 예약은 원 query identity와 실제 Job에 고정된다")
    void queryReservationIdentityCannotBeRebound() throws Exception {
        var roles=new java.util.EnumMap<org.monitoring.catchholebackend.domain.validation.type.ValidationRole,org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.RoleBudget>(executionProperties.roles());
        roles.put(org.monitoring.catchholebackend.domain.validation.type.ValidationRole.QUERY_EMBEDDING,new org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.RoleBudget(1,100));
        when(executionProperties.roles()).thenReturn(roles);when(executionProperties.queryEmbeddingModel()).thenReturn("text-embedding-3-small");
        start();var job=claim();UUID request=UUID.randomUUID();
        var proof=new LinkedHashMap<String,Object>(Map.of("contractVersion",1,"inputDigest",job.at("/scope/inputDigest").asText(),"queryHash","a".repeat(64),"indexModel","text-embedding-3-small","indexVersion","v1","indexDimensions",1536,"manifestHash",job.path("manifestHash").asText()));
        var body=new LinkedHashMap<String,Object>(Map.of("scope",scope(job),"requestId",request,"role","QUERY_EMBEDDING","model","text-embedding-3-small","reservedTokens",30,"queryInput",proof));
        internal(path(job,"/usage/reserve"),job,body,200);proof.put("queryHash","b".repeat(64));
        internal(path(job,"/usage/reserve"),job,body,409);noExtraction();
    }

}
