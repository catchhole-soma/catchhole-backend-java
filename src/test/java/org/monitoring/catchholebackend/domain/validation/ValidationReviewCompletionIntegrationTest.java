package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobWorkerService;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** GH312 S2: receipt와 추출 Job은 한 Work 잠금 트랜잭션에서 함께 생기며 그 전에는 추출·후보·자동 확정이 0이다. */
@SpringBootTest(properties="spring.config.import=") @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@DisplayName("GH312 S2 검토 종료 receipt와 설정 추출 release")
class ValidationReviewCompletionIntegrationTest extends ValidationReviewFixture {
    @Autowired AnalysisJobWorkerService worker;

    JsonNode completeReview(UUID report,long version,String key,int status) throws Exception {
        var result=mvc.perform(post(base()+"/"+report+"/review-completion").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedReportVersion",version,"idempotencyKey",key)))).andExpect(status().is(status)).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path(status==200?"data":"error");
    }
    JsonNode analysis(String path,Object body,int status) throws Exception {
        var request=post("/api/v1/works/"+work.getId()+"/analysis-jobs"+path).header("Authorization","Bearer "+token);
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        var result=mvc.perform(request).andExpect(status().is(status)).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path(status==200?"data":"error");
    }
    JsonNode createExtraction(int status) throws Exception {return analysis("",Map.of("jobType","SETTING_EXTRACTION","batchId",batchId,"episodeId",target.getId()),status);}
    long version(UUID report) throws Exception {return get(report).path("reportVersion").asLong();}
    void judgeAll(UUID report) throws Exception {
        for(var finding:findings(report,"?size=100").path("content"))judge(report,finding,finding.path("branch").asText().equals("SETTING")?"NOT_CONFLICT":"KEEP_ORIGINAL");
    }
    Object[] receiptRow(UUID report) {
        return em.createQuery("select c.extractionJob.id,c.extractionJobGeneration,c.trigger,c.reportVersion,c.idempotencyKey from ValidationReviewCompletion c where c.report.id=:r",Object[].class).setParameter("r",report).getSingleResult();
    }
    /** 파일 교체는 새 업로드 batch를 만든다. 일반 생성은 회차의 현재 원본 batch로 요청한다. */
    UUID currentBatch() {return em.createQuery("select f.batch.id from UploadFile f where f.id=(select e.sourceFileId from Episode e where e.id=:e)",UUID.class).setParameter("e",target.getId()).getSingleResult();}
    WorkerAnalysisJobClaimRequest claimRequest() {
        return new WorkerAnalysisJobClaimRequest("synthetic-no-llm","검토 종료 release",Set.of(AnalysisJobType.SETTING_EXTRACTION),Set.of(AnalysisMode.CONFIRMED_ONLY,AnalysisMode.ORDERED_PROVISIONAL),
                true,Set.of(AnalysisSourceKind.EPISODE),true,true,true);
    }
    AnalysisJob syntheticExtraction() {
        var job=AnalysisJob.create(em.find(Work.class,work.getId()),em.find(UploadBatch.class,batchId),em.find(Episode.class,target.getId()),AnalysisJobType.SETTING_EXTRACTION);em.persist(job);em.flush();return job;
    }

    @Test @DisplayName("정상 0건 완료는 /complete가 receipt와 추출 Job 하나를 함께 만들고 재전송은 같은 receipt다")
    void zeroFindingsAutoStartsExtraction() throws Exception {
        source();start();var job=claim();prePlan(job,List.of());savePlan(job,"MERGED_PLAN","PLAN",Map.of("questions",List.of()));
        internal(path(job,"/units"),job,unit(job,"TYPO","TSEG1","COMPLETED",typoEmpty()),200);var report=reportId(job);
        var finish=Map.of("scope",scope(job),"idempotencyKey","empty");var receipt=internal(path(job,"/complete"),job,finish,200);
        assertThat(receipt.path("status").asText()).isEqualTo("COMPLETED");var release=releaseOnly();
        assertThat(receipt.path("extractionJobId").asText()).isEqualTo(release.toString());assertThat(receipt.path("reviewCompletionId").isTextual()).isTrue();
        var extraction=em.find(AnalysisJob.class,release);
        assertThat(extraction.getStatus()).isEqualTo(AnalysisJobStatus.PENDING);assertThat(extraction.getEpisode().getId()).isEqualTo(target.getId());assertThat(extraction.getBatch().getId()).isEqualTo(batchId);
        assertThat(extraction.getReviewMode()).as("single new-episode upload default").isEqualTo(AnalysisReviewMode.AUTOMATIC);assertThat(extraction.getAnalysisMode()).isEqualTo(AnalysisMode.ORDERED_PROVISIONAL);
        var summary=review(report);
        assertThat(summary.path("completionMode").asText()).isEqualTo("COMPLETED");assertThat(summary.path("canCompleteReview").asBoolean()).isFalse();assertThat(texts(summary.path("allowedActions"))).as("GH312 S3: 반영 전에는 회차 전체 반영만 안내").containsExactly("APPLY_SETTINGS");
        assertThat(summary.path("application").isNull()).isTrue();assertThat(summary.path("extractionState").asText()).isEqualTo("RUNNING");
        assertThat(summary.path("lockReason").asText()).isEqualTo("REVIEW_COMPLETED");assertThat(summary.path("completion").path("trigger").asText()).isEqualTo("AUTOMATIC");
        assertThat(summary.path("completion").path("completionId")).isEqualTo(receipt.path("reviewCompletionId"));assertThat(summary.path("completion").path("extractionJobId").asText()).isEqualTo(release.toString());
        var row=receiptRow(report);assertThat(row[2].toString()).isEqualTo("AUTOMATIC");assertThat(row[4]).isNull();
        assertThat(((Number)row[3]).longValue()).as("receipt keeps the evaluated version before its own advance").isEqualTo(summary.path("reportVersion").asLong()-1);
        assertThat(receipt.path("reportVersion").asLong()).isEqualTo(summary.path("reportVersion").asLong());
        var staging=get(report);assertThat(staging.path("extractionJobId").asText()).isEqualTo(release.toString());assertThat(staging.path("extractionStatus").asText()).isEqualTo("PENDING");
        assertThat(staging.path("errorReviewStatus").asText()).isEqualTo("READY");assertThat(staging.path("candidateStatus").asText()).isEqualTo("NOT_STARTED");
        assertThat(internal(path(job,"/complete"),job,finish,200)).isEqualTo(receipt);releaseOnly();
        assertThat(code(completeReview(report,version(report),"late-explicit",409))).isEqualTo("VALIDATION_REVIEW_ALREADY_COMPLETED");releaseOnly();
    }

    @Test @DisplayName("모든 항목을 충돌 아님·원문 유지로 판단해도 명시 검토 완료 전에는 Job이 없고 같은 키만 재전송된다")
    void allNotConflictNeedsExplicitCompletion() throws Exception {
        var report=reportId(completedWithFindings(2));judgeAll(report);noExtraction();
        var summary=review(report);assertThat(summary.path("completionMode").asText()).isEqualTo("EXPLICIT_REQUIRED");assertThat(texts(summary.path("allowedActions"))).contains("COMPLETE_REVIEW");
        long v=summary.path("reportVersion").asLong();
        var first=completeReview(report,v,"finish-review",200);var release=releaseOnly();
        assertThat(first.path("trigger").asText()).isEqualTo("EXPLICIT");assertThat(first.path("reportId").asText()).isEqualTo(report.toString());assertThat(first.path("reportVersion").asLong()).isEqualTo(v);
        assertThat(first.path("extractionJobId").asText()).isEqualTo(release.toString());assertThat(first.path("completedAt").isTextual()).isTrue();
        assertThat(completeReview(report,v,"finish-review",200)).isEqualTo(first);releaseOnly();
        assertThat(code(completeReview(report,v+1,"finish-review",409))).isEqualTo("VALIDATION_IDEMPOTENCY_CONFLICT");releaseOnly();
        assertThat(code(completeReview(report,version(report),"another-key",409))).isEqualTo("VALIDATION_REVIEW_ALREADY_COMPLETED");releaseOnly();
        var done=review(report);assertThat(done.path("completionMode").asText()).isEqualTo("COMPLETED");assertThat(done.path("completion").path("trigger").asText()).isEqualTo("EXPLICIT");
        assertThat(done.path("reportVersion").asLong()).isEqualTo(v+1);assertThat(texts(done.path("allowedActions"))).doesNotContain("SAVE_JUDGMENT","COMPLETE_REVIEW");
        assertThat(receiptRow(report)[4]).isEqualTo("finish-review");
    }

    @Test @DisplayName("부분 완료·미검토·수정 필요가 남으면 검토 완료를 거절하고 Job을 만들지 않는다")
    void incompleteReviewCannotCreateExtraction() throws Exception {
        var job=begin();for(var check:plan(job,1))decide(job,check,false);var report=reportId(job);assertThat(finish(job,"typo-missing")).isEqualTo("PARTIAL");
        judge(report,only(report,"SETTING"),"NOT_CONFLICT");
        var partial=completeReview(report,version(report),"partial",409);assertThat(code(partial)).isEqualTo("VALIDATION_REVIEW_NOT_COMPLETABLE");
        assertThat(texts(partial.path("context").path("holdReasons"))).containsExactly("EXECUTION_NOT_COMPLETED");noExtraction();
        retry(report,"finish-typo");var next=claim();typos(next);assertThat(finish(next,"done")).isEqualTo("COMPLETED");noExtraction();
        var pending=completeReview(report,version(report),"pending",409);assertThat(code(pending)).isEqualTo("VALIDATION_REVIEW_NOT_COMPLETABLE");
        assertThat(texts(pending.path("context").path("holdReasons"))).containsExactly("PENDING_JUDGMENTS");noExtraction();
        judgeAll(report);judge(report,findings(report,"?branch=TYPO").path("content").path(0),"NEEDS_FIX");
        var fix=completeReview(report,version(report),"needs-fix",409);assertThat(code(fix)).isEqualTo("VALIDATION_REVIEW_NOT_COMPLETABLE");
        assertThat(texts(fix.path("context").path("holdReasons"))).containsExactly("NEEDS_FIX_PENDING");assertThat(fix.path("context").path("completionMode").asText()).isEqualTo("BLOCKED");
        assertThat(count("validation_review_completions",report)).isZero();noExtraction();
    }

    @Test @DisplayName("오래된 report version의 검토 완료는 거절하고 현재 version만 receipt를 만든다")
    void staleReportVersionIsRejected() throws Exception {
        var report=reportId(completedWithFindings(1));judgeAll(report);long v=version(report);
        assertThat(code(completeReview(report,v-1,"stale",409))).isEqualTo("VALIDATION_REPORT_VERSION_CONFLICT");noExtraction();
        assertThat(count("validation_review_completions",report)).isZero();
        assertThat(completeReview(report,v,"fresh",200).path("reportVersion").asLong()).isEqualTo(v);releaseOnly();
    }

    @Test @DisplayName("원고를 교체한 옛 report는 검토를 완료할 수 없고 재검수의 새 report도 receipt 없이 막힌다")
    void staleSourceCannotCreateExtraction() throws Exception {
        var report=reportId(completedWithFindings(1));judgeAll(report);assertThat(review(report).path("completionMode").asText()).isEqualTo("EXPLICIT_REQUIRED");
        var original=get(report);replace("수정한 합성 원고");
        org.mockito.Mockito.when(storage.getText(org.mockito.ArgumentMatchers.anyString())).thenAnswer(c->c.getArgument(0).equals(target.getContentS3Key())?"수정한 합성 원고":"합성 앞 원고");
        var stale=completeReview(report,version(report),"after-replace",409);assertThat(code(stale)).isEqualTo("VALIDATION_REVIEW_NOT_COMPLETABLE");
        assertThat(texts(stale.path("context").path("holdReasons"))).contains("REPORT_INVALIDATED");noExtraction();
        var fresh=UUID.fromString(postOk(recheckPath(original),recheck(original,"after-fix")).path("reportId").asText());
        var next=review(fresh);assertThat(next.path("completion").isNull()).isTrue();assertThat(texts(next.path("holdReasons"))).contains("EXECUTION_NOT_COMPLETED");
        assertThat(currentBatch()).as("replacement creates a new upload batch").isNotEqualTo(batchId);
        assertThat(code(analysis("",Map.of("jobType","SETTING_EXTRACTION","batchId",currentBatch(),"episodeId",target.getId()),409))).isEqualTo("VALIDATION_ERROR_REVIEW_PENDING");
        assertThat(code(completeReview(report,version(report),"retired",409))).isEqualTo("VALIDATION_REVIEW_NOT_COMPLETABLE");
        assertThat(code(completeReview(fresh,version(fresh),"not-run",409))).isEqualTo("VALIDATION_REVIEW_NOT_COMPLETABLE");
        assertThat(em.createNativeQuery("select count(*) from validation_review_completions").getSingleResult()).isEqualTo(0L);noExtraction();
    }

    @Test @DisplayName("진단 report는 모든 항목을 판단해도 검토 종료와 추출을 만들지 않는다")
    void diagnosticCannotComplete() throws Exception {
        source();priorHash=sha("합성 앞 원고");prior.updateContentStorage(prior.getContentS3Key(),prior.getContentS3Version(),priorHash);em.flush();
        var job=diagnosticStart();for(var check:plan(job,1))decide(job,check,false);typos(job);var report=reportId(job);assertThat(finish(job,"done")).isEqualTo("COMPLETED");noExtraction();
        judgeAll(report);var summary=review(report);assertThat(summary.path("completionMode").asText()).isEqualTo("BLOCKED");assertThat(texts(summary.path("allowedActions"))).doesNotContain("COMPLETE_REVIEW");
        var refused=completeReview(report,version(report),"diagnostic",409);assertThat(code(refused)).isEqualTo("VALIDATION_REVIEW_NOT_COMPLETABLE");
        assertThat(texts(refused.path("context").path("holdReasons"))).containsExactly("DIAGNOSTIC_ONLY");noExtraction();
    }

    @Test @DisplayName("검토 종료 뒤에도 공개 생성은 막고 Worker claim은 release Job만 받는다")
    void completedReportBlocksPublicCreateButReleasesClaim() throws Exception {
        var report=reportId(completedWithFindings(1));judgeAll(report);
        var blocked=syntheticExtraction();
        assertThat(worker.claimAnalysisJob(claimRequest())).as("before completion the episode is not claimable").isEmpty();
        assertThat(em.find(AnalysisJob.class,blocked.getId()).getStatus()).isEqualTo(AnalysisJobStatus.PENDING);em.remove(em.find(AnalysisJob.class,blocked.getId()));em.flush();noExtraction();
        var receipt=completeReview(report,version(report),"release",200);var release=releaseOnly();
        assertThat(code(createExtraction(409))).isEqualTo("VALIDATION_ERROR_REVIEW_PENDING");releaseOnly();
        var claimed=worker.claimAnalysisJob(claimRequest());
        assertThat(claimed).isPresent();assertThat(claimed.get().analysisJobId().toString()).isEqualTo(receipt.path("extractionJobId").asText()).isEqualTo(release.toString());
        assertThat(get(report).path("extractionStatus").asText()).isEqualTo("PENDING");
    }

    @Test @DisplayName("검토 종료 뒤 판단 변경·같은 입력 재시도·진행 중 release의 재검수를 거절한다")
    void judgmentAndRetryLockedAfterCompletion() throws Exception {
        var report=reportId(completedWithFindings(1));judgeAll(report);completeReview(report,version(report),"lock",200);releaseOnly();
        var finding=only(report,"SETTING");
        assertThat(code(save(report,finding,"NEEDS_FIX",finding.path("judgment").path("version").asLong(),1L,409))).isEqualTo("VALIDATION_JUDGMENT_LOCKED");
        var summary=review(report);assertThat(summary.path("judgmentLocked").asBoolean()).isTrue();assertThat(summary.path("lockReason").asText()).isEqualTo("REVIEW_COMPLETED");
        conflict("/"+report+"/retries",Map.of("expectedReportVersion",version(report),"idempotencyKey","after-completion"),"VALIDATION_REVIEW_ALREADY_COMPLETED");
        mvc.perform(post(base()+recheckPath(get(report))).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(recheck(get(report),"while-release"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_TARGET_INVALID"));
        assertThat(only(report,"SETTING").path("judgment").path("value").asText()).isEqualTo("NOT_CONFLICT");releaseOnly();
    }

    @Test @DisplayName("release Job 재시도만 허용하고 새 Job이면 receipt 연결과 세대를 옮긴다")
    void retryOfReleaseJobKeepsLink() throws Exception {
        var report=zeroFindingCompletion();var release=releaseOnly();
        em.find(AnalysisJob.class,release).fail("합성 실패");em.flush();
        var resumed=analysis("/"+release+"/retry",null,200);
        assertThat(resumed.path(0).path("id").asText()).as("ordered release resumes the same job").isEqualTo(release.toString());
        assertThat(em.find(AnalysisJob.class,release).getStatus()).isEqualTo(AnalysisJobStatus.PENDING);
        var row=receiptRow(report);assertThat(row[0]).isEqualTo(release);assertThat(((Number)row[1]).longValue()).isEqualTo(1);
        // 기본 release는 ORDERED_PROVISIONAL이라 같은 Job을 재개한다. 새 Job을 만드는 재시도 연결은 합성 비순차 Job으로 검증한다.
        em.find(AnalysisJob.class,release).fail("합성 실패");var linked=syntheticExtraction();linked.fail("합성 실패");em.flush();
        em.createNativeQuery("update validation_review_completions set extraction_job_id=:j where report_id=:r").setParameter("j",linked.getId()).setParameter("r",report).executeUpdate();em.clear();
        var replaced=UUID.fromString(analysis("/"+linked.getId()+"/retry",null,200).path(0).path("id").asText());
        assertThat(replaced).isNotEqualTo(linked.getId()).isNotEqualTo(release);em.flush();
        row=receiptRow(report);assertThat(row[0]).isEqualTo(replaced);assertThat(((Number)row[1]).longValue()).isEqualTo(2);
        assertThat(get(report).path("extractionJobId").asText()).isEqualTo(replaced.toString());
        assertThat(code(analysis("/"+release+"/retry",null,409))).as("a failed job no longer linked to the receipt").isEqualTo("VALIDATION_ERROR_REVIEW_PENDING");
        assertThat(em.find(AnalysisJob.class,release).getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
    }

    @Test @DisplayName("무효화는 receipt를 이력으로 남기고 작품 파기는 receipt를 Job·report보다 먼저 지운다")
    void workPurgeDeletesCompletionRows() throws Exception {
        var report=zeroFindingCompletion();var release=releaseOnly();assertThat(count("validation_review_completions",report)).isEqualTo(1);
        // 진행 중인 release Job이 있으면 기존 정책대로 원고 교체를 거절하므로 실패 상태로 둔다.
        em.find(AnalysisJob.class,release).fail("합성 실패");em.flush();replace("수정한 합성 원고");
        assertThat(count("validation_review_completions",report)).as("invalidation keeps the receipt as history").isEqualTo(1);
        var invalid=review(report);assertThat(invalid.path("lockReason").asText()).isEqualTo("REPORT_INVALIDATED");assertThat(texts(invalid.path("allowedActions"))).containsExactly("RECHECK");
        em.flush();em.clear();workPurge.purgeWorkData(work.getId());
        for(String table:List.of("validation_review_completions","analysis_jobs","validation_reports"))
            assertThat(em.createNativeQuery("select count(*) from "+table).getSingleResult()).as(table).isEqualTo(0L);
    }
}
