package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    @Autowired org.monitoring.catchholebackend.domain.validation.processor.ValidationLiveSettings liveSettings;
    @Autowired org.monitoring.catchholebackend.domain.validation.processor.ValidationSourceManifests sourceManifests;
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
        assertThat(stored(next).getSourceContentS3Key()).isEqualTo(target.getContentS3Key()); assertThat(stored(next).getBaselineSeed()).isNull();
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
    @Test @DisplayName("실제 확정 이력이 있으면 진단 재검수가 되고 현재 확정 설정으로 바로 준비된다")
    void actualConfirmedHistorySelectsDiagnosticMode() throws Exception {
        var first=start();
        var job=AnalysisJob.create(work,null,target,AnalysisJobType.SETTING_EXTRACTION); em.persist(job);
        var confirmed=WorldSettingCandidate.create(work,target,job,WorldSettingCategory.RACE,"합성 종족","서식지","북부",JsonNodeFactory.instance.arrayNode(),BigDecimal.ONE,null);
        ReflectionTestUtils.setField(confirmed,"reviewStatus",WorldSettingReviewStatus.CONFIRMED); em.persist(confirmed); job.succeed(null,0,0); em.flush();
        replace("확정 뒤 고친 원고"); var result=postOk(recheckPath(first),recheck(first,"diagnostic"));
        assertThat(result.path("mode").asText()).isEqualTo("DIAGNOSTIC_ONLY");
        // #381 소유자 결정: 진단도 기록 입력이 아니라 현재 확정 설정과 대조하므로 직전 반영 Job의 입력을 입증하지 못해도 보류하지 않는다.
        assertThat(result.path("status").asText()).isEqualTo("WAITING_FOR_ERROR_REVIEW"); assertThat(result.path("baselineStatus").asText()).isEqualTo("READY");
        assertThat(result.path("holdReason").isNull()).isTrue();
        assertThat(result.path("previousApplicationReceipt").path("applicationId").asText()).isEqualTo(confirmed.getId().toString());
        assertThat(result.path("previousApplicationReceipt").path("analysisJobId").asText()).isEqualTo(job.getId().toString());
        assertThat(result.path("extractionJobId").isNull()).isTrue(); assertThat(result.path("candidateGeneration").isNull()).isTrue();
        assertThat(confirmed.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED);
        assertThat(em.createQuery("select count(j) from AnalysisJob j",Long.class).getSingleResult()).isEqualTo(1);
    }
    @Test @DisplayName("Job 없는 legacy 캐릭터 확정도 진단 재검수를 고르고 seed 없이 현재 확정 설정으로 준비된다")
    void legacyCharacterConfirmationWithoutJobRechecksAgainstCurrentSettings() throws Exception {
        var first=start();
        var confirmed=org.monitoring.catchholebackend.domain.character.entity.SettingCandidate.create(work,target,null,null,
                org.monitoring.catchholebackend.domain.character.type.SettingEntityType.CHARACTER,"합성 인물","합성 인물",character.getId(),
                org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.MATCHED,"age","20",
                org.monitoring.catchholebackend.domain.character.type.SettingValueType.NUMBER,JsonNodeFactory.instance.objectNode().put("value",20),
                JsonNodeFactory.instance.arrayNode(),BigDecimal.ONE,null);
        ReflectionTestUtils.setField(confirmed,"reviewStatus",org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.CONFIRMED);em.persist(confirmed);
        states.confirmedSettingsChanged(work.getId());world.updateProperty("서식지","서식지","작가가 확정한 현재값");em.flush();
        var result=postOk(recheckPath(first),recheck(first,"legacy-no-job"));
        assertThat(result.path("mode").asText()).isEqualTo("DIAGNOSTIC_ONLY");
        assertThat(result.path("previousApplicationReceipt").path("kind").asText()).isEqualTo("CHARACTER_CONFIRMATION");
        assertThat(result.path("previousApplicationReceipt").path("applicationId").asText()).isEqualTo(confirmed.getId().toString());
        assertThat(result.path("previousApplicationReceipt").path("analysisJobId").isNull()).isTrue();
        assertThat(result.path("status").asText()).isEqualTo("WAITING_FOR_ERROR_REVIEW");assertThat(result.path("holdReason").isNull()).isTrue();
        assertThat(stored(result).getBaselineSeed()).isNull();assertThat(stored(result).getBaselineEntries()).isNull();
        assertThat(result.path("baselineHash").asText()).isEqualTo(liveSettings.fingerprint(work.getId()));
        assertThat(result.path("extractionJobId").isNull()).isTrue();assertThat(result.path("candidateGeneration").isNull()).isTrue();
        assertThat(em.createQuery("select count(j) from AnalysisJob j",Long.class).getSingleResult()).isZero();
        assertThat(em.createQuery("select count(c) from SettingCandidate c",Long.class).getSingleResult()).isEqualTo(1);
        assertThat(em.createQuery("select count(c) from WorldSettingCandidate c",Long.class).getSingleResult()).isZero();
    }

    AnalysisJob appliedJob() {
        var job=AnalysisJob.create(work,null,target,AnalysisJobType.SETTING_EXTRACTION);
        job.configureReviewMode(AnalysisReviewMode.AUTOMATIC); states.initializeRun(List.of(job));
        job.claim("synthetic-no-llm","합성 완료",java.time.LocalDateTime.now().plusMinutes(1));
        assertThat(states.prepareInput(job)).isTrue(); states.seal(job); job.completeAutomaticApplication(); job.succeed(null,0,0); em.flush(); return job;
    }
    @Test @DisplayName("진단 재검수는 직전 반영의 기록 입력이 아니라 현재 확정 설정의 fingerprint로 준비하고 대상 회차를 출처에 넣지 않는다")
    void diagnosticRecheckUsesCurrentConfirmedSettings() throws Exception {
        var first=start(); var job=appliedJob();
        states.confirmedSettingsChanged(work.getId()); world.updateProperty("서식지","서식지","101화가 반영한 현재값"); em.flush();
        var result=postOk(recheckPath(first),recheck(first,"pre-application"));
        assertThat(result.path("mode").asText()).isEqualTo("DIAGNOSTIC_ONLY"); assertThat(result.path("baselineStatus").asText()).isEqualTo("READY");
        assertThat(result.path("previousApplicationReceipt").path("applicationId").asText()).isEqualTo(job.getId().toString());
        var report=stored(result);
        assertThat(report.getBaselineSeed()).isNull(); assertThat(report.getBaselineEntries()).isNull();
        assertThat(report.getBaselineHash()).isEqualTo(liveSettings.fingerprint(work.getId())).isEqualTo(report.getBaselineFingerprint()).isEqualTo(report.getConfirmedStateHash());
        assertThat(report.getPreviousApplicationJobId()).isEqualTo(job.getId()); assertThat(report.getPreviousApplicationAt()).isNotNull();
        assertThat(sourceManifests.manifests(report.getId()).findValuesAsText("episodeNo")).containsExactly("100");
        // 판정 입력은 현재 확정 설정이다: 작가가 반영한 값을 그대로 읽는다.
        var index=liveSettings.index(work.getId());
        assertThat(liveSettings.entries(index,List.of(index.subjects().stream().filter(s->s.id().equals(world.getId())).findFirst().orElseThrow().subjectRef())).toString())
                .contains("101화가 반영한 현재값");
        assertThat(job.getAutomaticAppliedAt()).isNotNull(); assertThat(em.createQuery("select count(j) from AnalysisJob j",Long.class).getSingleResult()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    @DisplayName("회차·설정집 출처의 확정 이력이 있어도 진단 재검수는 기록 입력을 검사하지 않고 준비된다")
    void diagnosticRecheckPreparesWithoutInspectingRecordedSources(boolean bookSource) throws Exception {
        AnalysisJob source;
        if(bookSource) {
            var batch=org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.create(work,member,org.monitoring.catchholebackend.domain.upload.type.UploadType.SETTING_BOOK,org.monitoring.catchholebackend.domain.upload.type.UploadSourceType.FILE);em.persist(batch);
            var book=org.monitoring.catchholebackend.domain.upload.entity.UploadFile.create(batch,org.monitoring.catchholebackend.domain.upload.type.UploadFileRole.SETTING_BOOK,"synthetic-book.txt","text/plain","synthetic/book",1);em.persist(book);
            var revision=org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision.create(book,1,new org.monitoring.catchholebackend.global.storage.StoredTextObject("synthetic/book-r1","v1","b".repeat(64),1),org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind.WORK_START,null);em.persist(revision);book.linkCurrentRevision(revision);
            source=AnalysisJob.createSettingBook(work,book,revision);
        } else source=AnalysisJob.create(work,null,prior,AnalysisJobType.SETTING_EXTRACTION);
        em.persist(source);source.succeed(null,0,0);
        var confirmed=WorldSettingCandidate.create(work,bookSource?null:prior,source,WorldSettingCategory.RACE,"합성 종족","서식지","북부",JsonNodeFactory.instance.arrayNode(),BigDecimal.ONE,null);
        ReflectionTestUtils.setField(confirmed,"reviewStatus",WorldSettingReviewStatus.CONFIRMED);ReflectionTestUtils.setField(confirmed,"targetWorldSetting",world);
        ReflectionTestUtils.setField(confirmed,"finalCategory",WorldSettingCategory.RACE);ReflectionTestUtils.setField(confirmed,"finalSubjectName","합성 종족");
        ReflectionTestUtils.setField(confirmed,"finalSettingName","서식지");ReflectionTestUtils.setField(confirmed,"finalValue","북부");ReflectionTestUtils.setField(confirmed,"finalOperation",WorldSettingOperation.ADD);em.persist(confirmed);em.flush();
        var first=start();var applied=appliedJob();states.confirmedSettingsChanged(work.getId());em.flush();
        var result=postOk(recheckPath(first),recheck(first,"unchanged-source"));
        assertThat(result.path("mode").asText()).isEqualTo("DIAGNOSTIC_ONLY");assertThat(result.path("baselineStatus").asText()).isEqualTo("READY");
        assertThat(stored(result).getBaselineSeed()).isNull();
        assertThat(sourceManifests.manifests(stored(result).getId()).findValuesAsText("sourceKind")).containsExactlyElementsOf(bookSource?List.of("EPISODE","SETTING_BOOK"):List.of("EPISODE"));
        assertThat(result.path("previousApplicationReceipt").path("applicationId").asText()).isEqualTo(applied.getId().toString());
        assertThat(em.createQuery("select count(j) from AnalysisJob j",Long.class).getSingleResult()).isEqualTo(2);
        assertThat(em.createQuery("select count(c) from WorldSettingCandidate c",Long.class).getSingleResult()).isEqualTo(1);
    }
    @Test @DisplayName("직전 반영 입력의 근거가 파기돼도 진단 재검수는 현재 확정 설정으로 준비되고 늦은 정규화는 아무것도 바꾸지 않는다")
    void purgedPreApplicationInputDoesNotHoldDiagnosticRecheck() throws Exception {
        var first=start(); var job=appliedJob();
        states.purgeSourceEvidenceForWorkForUpdate(work.getId(),prior.getId(),100); em.flush();
        var result=postOk(recheckPath(first),recheck(first,"purged-diagnostic"));
        assertThat(result.path("mode").asText()).isEqualTo("DIAGNOSTIC_ONLY"); assertThat(result.path("baselineStatus").asText()).isEqualTo("READY");
        assertThat(result.path("holdReason").isNull()).isTrue();
        var ready=stored(result);var version=ready.getVersion();
        assertThat(ready.getBaselineSeed()).isNull(); baselines.materialize(ready.getId()); assertThat(ready.getBaselineSeed()).isNull();
        assertThat(ready.getBaselineEntries()).isNull(); assertThat(ready.getVersion()).isEqualTo(version);
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
        assertThat(stored(next).getBaselineHash()).isNotNull(); assertThat(stored(next).getStatus().name()).isEqualTo("WAITING_FOR_ERROR_REVIEW");
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
}
