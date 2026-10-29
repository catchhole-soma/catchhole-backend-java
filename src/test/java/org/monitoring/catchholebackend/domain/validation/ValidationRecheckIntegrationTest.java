package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.type.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
import org.monitoring.catchholebackend.domain.validation.event.ValidationStagingEvents;

@SpringBootTest(properties="spring.config.import=") @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class ValidationRecheckIntegrationTest extends ValidationRecheckFixture {
    @Autowired AnalysisRunStateService states;
    @Autowired org.springframework.context.ApplicationEventPublisher events;
    @Test void changedManuscriptCreatesNewReportWithoutExtraction() throws Exception {
        var first = start(); var before = stored(first); replace("바뀐 합성 원고");
        assertThat(before.getBaselineSeed()).isNull();
        assertThat(em.createQuery("select count(r) from ValidationReport r", Long.class).getSingleResult()).isEqualTo(1);
        var next = postOk(recheckPath(first), recheck(first, "changed"));
        assertThat(next.path("reportId")).isNotEqualTo(first.path("reportId"));
        assertThat(next.path("revision").asLong()).isEqualTo(2); assertThat(next.path("baselineRevision").asLong()).isEqualTo(2);
        assertThat(next.path("current").asBoolean()).isTrue(); assertThat(next.path("previousReportId")).isEqualTo(first.path("reportId"));
        assertThat(get(before.getId()).path("current").asBoolean()).isFalse();
        assertThat(stored(next).getSourceContentHash()).isEqualTo(target.getContentHash());
        assertThat(stored(next).getBaselineSeed().path("target").path("contentS3Key").asText()).isEqualTo(target.getContentS3Key());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/works/"+work.getId()+"/episodes").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].validationReportId").value(next.path("reportId").asText()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/works/"+work.getId()+"/analysis-jobs/batches").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].validationReports.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].validationReports[0].reportId").value(next.path("reportId").asText())); noExtraction();
    }
    @Test void recheckAdmissionIsIdempotent() throws Exception {
        var first=start(); replace("다른 원고"); var input=recheck(first,"repeat");
        var next=postOk(recheckPath(first),input); var duplicate=postOk(recheckPath(first),input);
        assertThat(duplicate).isEqualTo(next); assertThat(em.createQuery("select count(r) from ValidationReport r",Long.class).getSingleResult()).isEqualTo(2); noExtraction();
    }
    @Test void sameKeyDifferentInputRejected() throws Exception {
        var first=start(); replace("다른 원고"); var input=recheck(first,"repeat"); postOk(recheckPath(first),input);
        input.put("expectedContentHash","f".repeat(64)); conflict(recheckPath(first),input,"VALIDATION_IDEMPOTENCY_CONFLICT"); noExtraction();
    }
    @Test void clientModeCannotCreateDiagnosticAuthority() throws Exception {
        var first=start();replace("새 원고");var input=recheck(first,"client-mode");input.put("mode","DIAGNOSTIC_ONLY");
        var result=postOk(recheckPath(first),input);
        assertThat(result.path("mode").asText()).isEqualTo("BEFORE_CONFIRMATION");assertThat(result.path("previousApplicationReceipt").isNull()).isTrue();noExtraction();
    }
    @Test void staleReportVersionRejected() throws Exception {
        var first=start(); replace("다른 원고"); var input=recheck(first,"stale"); input.put("expectedReportVersion",first.path("reportVersion").asLong());
        conflict(recheckPath(first),input,"VALIDATION_REPORT_VERSION_CONFLICT"); noExtraction();
    }
    @Test void unchangedInputUsesSameReportRetryBoundary() throws Exception {
        var first=start(); conflict(recheckPath(first),recheck(first,"same"),"VALIDATION_RECHECK_INPUT_UNCHANGED"); noExtraction();
    }
    @Test void oldReportCannotBecomeCurrentAgain() throws Exception {
        var first=start(); replace("새 원고"); postOk(recheckPath(first),recheck(first,"new"));
        conflict(recheckPath(first),recheck(first,"old"),"VALIDATION_REPORT_VERSION_CONFLICT"); noExtraction();
    }
    @Test void actualConfirmedHistorySelectsDiagnosticMode() throws Exception {
        var first=start();
        var job=AnalysisJob.create(work,null,target,AnalysisJobType.SETTING_EXTRACTION); em.persist(job);
        var confirmed=WorldSettingCandidate.create(work,target,job,WorldSettingCategory.RACE,"합성 종족","서식지","북부",JsonNodeFactory.instance.arrayNode(),BigDecimal.ONE,null);
        ReflectionTestUtils.setField(confirmed,"reviewStatus",WorldSettingReviewStatus.CONFIRMED); em.persist(confirmed); job.succeed(null,0,0); em.flush();
        replace("확정 뒤 고친 원고"); var result=postOk(recheckPath(first),recheck(first,"diagnostic"));
        assertThat(result.path("mode").asText()).isEqualTo("DIAGNOSTIC_ONLY");
        assertThat(result.path("status").asText()).isEqualTo("HELD_INCOMPLETE");
        assertThat(result.path("holdReason").asText()).isEqualTo("DIAGNOSTIC_BASELINE_UNAVAILABLE");
        assertThat(result.path("previousApplicationReceipt").path("applicationId").asText()).isEqualTo(confirmed.getId().toString());
        assertThat(result.path("previousApplicationReceipt").path("analysisJobId").asText()).isEqualTo(job.getId().toString());
        assertThat(result.path("extractionJobId").isNull()).isTrue(); assertThat(result.path("candidateGeneration").isNull()).isTrue();
        assertThat(confirmed.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED);
        assertThat(em.createQuery("select count(j) from AnalysisJob j",Long.class).getSingleResult()).isEqualTo(1);
    }

    AnalysisJob appliedJob() {
        var job=AnalysisJob.create(work,null,target,AnalysisJobType.SETTING_EXTRACTION);
        job.configureReviewMode(AnalysisReviewMode.AUTOMATIC); states.initializeRun(List.of(job));
        job.claim("synthetic-no-llm","합성 완료",java.time.LocalDateTime.now().plusMinutes(1));
        assertThat(states.prepareInput(job)).isTrue(); states.seal(job); job.completeAutomaticApplication(); job.succeed(null,0,0); em.flush(); return job;
    }
    @Test void diagnosticUsesRecordedPreApplicationInputAndExcludesTargetSelfEvidence() throws Exception {
        var first=start(); var job=appliedJob();
        states.confirmedSettingsChanged(work.getId()); world.updateProperty("서식지","서식지","101화가 반영한 현재값"); em.flush();
        var result=postOk(recheckPath(first),recheck(first,"pre-application"));
        assertThat(result.path("mode").asText()).isEqualTo("DIAGNOSTIC_ONLY"); assertThat(result.path("baselineStatus").asText()).isEqualTo("READY");
        assertThat(result.path("previousApplicationReceipt").path("applicationId").asText()).isEqualTo(job.getId().toString());
        assertThat(stored(result).getBaselineSeed().path("worldSettings").toString()).contains("북부").doesNotContain("101화가 반영한 현재값");
        assertThat(stored(result).getBaselineSeed().path("sources").findValuesAsText("episodeNo")).doesNotContain("101");
        assertThat(job.getAutomaticAppliedAt()).isNotNull(); assertThat(em.createQuery("select count(j) from AnalysisJob j",Long.class).getSingleResult()).isEqualTo(1);
    }
    @Test void purgedPreApplicationInputCannotRestoreDiagnosticBaseline() throws Exception {
        var first=start(); var job=appliedJob();
        states.purgeSourceEvidenceForWorkForUpdate(work.getId(),prior.getId(),100); em.flush();
        var result=postOk(recheckPath(first),recheck(first,"purged-diagnostic"));
        assertThat(result.path("mode").asText()).isEqualTo("DIAGNOSTIC_ONLY"); assertThat(result.path("holdReason").asText()).isEqualTo("DIAGNOSTIC_BASELINE_UNAVAILABLE");
        var held=stored(result);var version=held.getVersion();
        assertThat(held.getBaselineSeed()).isNull(); baselines.materialize(held.getId()); assertThat(held.getBaselineSeed()).isNull();
        assertThat(held.getHoldReason()).isEqualTo("DIAGNOSTIC_BASELINE_UNAVAILABLE");assertThat(held.getVersion()).isEqualTo(version);
        assertThat(job.getAutomaticAppliedAt()).isNotNull();
    }
    @Test void inProgressLegacyExtractionIsNotCutOffByDiagnosticAdmission() throws Exception {
        var first=start(); var job=AnalysisJob.create(work,null,target,AnalysisJobType.SETTING_EXTRACTION); em.persist(job);
        var confirmed=WorldSettingCandidate.create(work,target,job,WorldSettingCategory.RACE,"합성 종족","서식지","북부",JsonNodeFactory.instance.arrayNode(),BigDecimal.ONE,null);
        ReflectionTestUtils.setField(confirmed,"reviewStatus",WorldSettingReviewStatus.CONFIRMED); em.persist(confirmed);
        events.publishEvent(new ValidationStagingEvents.ConfirmedSettingsChanged(work.getId())); em.flush();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base()+recheckPath(first)).header("Authorization","Bearer "+token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.writeValueAsString(recheck(first,"pending-legacy"))))
                .andExpect(status().isBadRequest());
        assertThat(job.getStatus().name()).isEqualTo("PENDING");
        assertThat(em.createQuery("select count(r) from ValidationReport r",Long.class).getSingleResult()).isEqualTo(1);
    }
    @Test void delayedReplacementCleanupKeepsTheNewCurrentReport() throws Exception {
        var first=start(); replace("보존할 새 원고"); var next=postOk(recheckPath(first),recheck(first,"retained"));
        states.purgeSourceEvidenceForWorkForUpdate(work.getId(),target.getId(),101,target.getContentS3Key()); em.flush();
        assertThat(stored(next).getBaselineSeed()).isNotNull(); assertThat(stored(next).getStatus().name()).isEqualTo("WAITING_FOR_ERROR_REVIEW");
        assertThat(stored(first).getBaselineSeed()).isNull(); noExtraction();
    }
    @Test void migratedInvalidatedReportWithoutFingerprintCanRecapture() throws Exception {
        var first=start();var original=stored(first);replace("다른 원고");
        ReflectionTestUtils.setField(original,"baselineFingerprint",null);em.flush();
        var input=recheck(first,"legacy-empty-hash");input.put("expectedBaselineHash",null);
        var next=postOk(recheckPath(first),input);assertThat(next.path("baselineStatus").asText()).isEqualTo("READY");noExtraction();
    }
    @Test void seedlessPreparationFailureCanCaptureNewBaseline() throws Exception {
        var batch=em.find(org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.class,batchId);
        var report=org.monitoring.catchholebackend.domain.validation.entity.ValidationReport.create(work,target,batch,"b".repeat(64));
        report.hold("BASELINE_SOURCE_UNAVAILABLE");em.persist(report);em.flush();var first=get(report.getId());
        var input=recheck(first,"seedless");input.put("expectedBaselineHash",null);
        var next=postOk(recheckPath(first),input);assertThat(next.path("baselineStatus").asText()).isEqualTo("READY");noExtraction();
    }
    @Test void nullHashCannotBypassExistingFingerprint() throws Exception {
        var first=start();replace("다른 원고");var input=recheck(first,"null-hash");input.put("expectedBaselineHash",null);
        conflict(recheckPath(first),input,"VALIDATION_SOURCE_VERSION_CONFLICT");noExtraction();
    }
    @Test void nullHashRecoveryStillRejectsStaleReportVersion() throws Exception {
        var first=start();var report=stored(first);replace("다른 원고");ReflectionTestUtils.setField(report,"baselineFingerprint",null);em.flush();
        var input=recheck(first,"old-null-hash");input.put("expectedBaselineHash",null);input.put("expectedReportVersion",first.path("reportVersion").asLong());
        conflict(recheckPath(first),input,"VALIDATION_REPORT_VERSION_CONFLICT");noExtraction();
    }
    @Test void staleTargetRevisionIsRejectedEvenWithCurrentHashKeyAndVersion() throws Exception {
        var first=start();replace("새 원고");var input=recheck(first,"old-revision");input.put("expectedContentRevision",0);
        conflict(recheckPath(first),input,"VALIDATION_SOURCE_VERSION_CONFLICT");noExtraction();
    }
    @Test void diagnosticBaselineCarriesOnlyTheActuallyCorrectedCurrentSlot() throws Exception {
        var first=start();appliedJob();states.confirmedSettingsChanged(work.getId());world.updateProperty("서식지","서식지","타겟 회차 반영값");em.flush();
        var diagnostic=postOk(recheckPath(first),recheck(first,"diagnostic-first"));
        var input=correction(diagnostic,"characters","diagnostic-age-fix");
        input.put("character",Map.of("targetId",character.getId(),"expectedSnapshotVersion",character.getSnapshotVersion(),"factType","AGE","factKey","age","edit",characterEdit(21,1)));
        var receipt=postOk(correctionPath(diagnostic),input);var command=recheck(diagnostic,"diagnostic-corrected");
        command.remove("expectedBaselineHash");command.put("correctionReceiptId",receipt.path("correctionId").asText());
        var result=postOk(recheckPath(diagnostic),command);var seed=stored(result).getBaselineSeed();
        assertThat(seed.path("characters").path("character:"+character.getId()).path("slots").path("AGE:age").path("factValue").asText()).isEqualTo("21");
        assertThat(seed.path("worldSettings").toString()).contains("북부").doesNotContain("타겟 회차 반영값");
        assertThat(seed.path("explicitAuthorCorrections").size()).isEqualTo(1);
        assertThat(seed.path("explicitAuthorCorrections").get(0).path("factKey").asText()).isEqualTo("age");
        var second=correction(result,"characters","diagnostic-second-age-fix");
        second.put("character",Map.of("targetId",character.getId(),"expectedSnapshotVersion",character.getSnapshotVersion(),"factType","AGE","factKey","age","edit",characterEdit(22,1)));
        var secondReceipt=postOk(correctionPath(result),second);var secondCommand=recheck(result,"diagnostic-corrected-again");
        secondCommand.remove("expectedBaselineHash");secondCommand.put("correctionReceiptId",secondReceipt.path("correctionId").asText());
        var again=postOk(recheckPath(result),secondCommand);
        assertThat(stored(again).getBaselineSeed().path("characters").path("character:"+character.getId()).path("slots").path("AGE:age").path("factValue").asText()).isEqualTo("22");
        assertThat(stored(again).getBaselineSeed().path("explicitAuthorCorrections").size()).isEqualTo(2);
    }
}
