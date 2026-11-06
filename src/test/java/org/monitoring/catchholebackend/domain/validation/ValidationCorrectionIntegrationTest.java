package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties="spring.config.import=") @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class ValidationCorrectionIntegrationTest extends ValidationRecheckFixture {
    @org.springframework.beans.factory.annotation.Autowired org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService states;
    /** report가 아직 유효(무효화 전)한지. #381 live report는 seed가 없으므로 상태로 본다. */
    boolean valid(com.fasterxml.jackson.databind.JsonNode response) {return stored(response).getStatus()!=org.monitoring.catchholebackend.domain.validation.type.ValidationReportStatus.INVALIDATED;}
    /** 지금 확정 설정의 세계관 행(Worker SETTINGS·상세 basis와 같은 행). 정정 뒤 새 report가 읽을 값·작가 정정 사실을 확인한다. */
    com.fasterxml.jackson.databind.JsonNode liveWorld() {
        var subject=liveSettings.index(work.getId()).subjects().stream().filter(x->x.id().equals(world.getId())).findFirst().orElseThrow();return liveRow(subject.entryRef());
    }
    Map<String,Object> worldCommand(long version) {
        return Map.of("targetId",world.getId(),"expectedVersion",version,"edit",Map.of("currentSettingName","서식지","settingName","서식지","settingValue","남부","version",version));
    }
    @Test void baselineCorrectionIsAtomicWithActualEdit() throws Exception {
        var first=start(); var request=correction(first,"worldSettings","fix"); request.put("worldSetting",worldCommand(world.getVersion()));
        var receipt=postOk(correctionPath(first),request);
        assertThat(world.getPropertyValue("서식지")).isEqualTo("남부"); assertThat(valid(first)).as("the real edit invalidates the report").isFalse();
        assertThat(receipt.path("correctionId").isTextual()).isTrue();
        var retry=postOk(correctionPath(first),request); assertThat(retry).isEqualTo(receipt);
        // 무효화된 report에 다른 정정을 더 보낼 수 없다.
        var second=correction(first,"worldSettings","fix-again");second.put("worldSetting",worldCommand(world.getVersion()));second.put("expectedBaselineHash",first.path("baselineHash").asText());
        conflict(correctionPath(first),second,"VALIDATION_REPORT_VERSION_CONFLICT");
        var recheck=recheck(first,"corrected"); recheck.remove("expectedBaselineHash"); recheck.put("correctionReceiptId",receipt.path("correctionId").asText());
        var next=postOk(recheckPath(first),recheck);
        // #381: 새 report는 seed 없이 현재 확정 설정을 읽는다. Worker가 받을 세계관 행에 고친 값과 작가 정정 사실(authorCorrectionProofs)이 있다.
        assertThat(stored(next).getBaselineSeed()).isNull();
        var row=liveWorld();assertThat(row.path("state").path("propertiesJson").path("서식지").asText()).isEqualTo("남부");
        var corrections=row.path("state").path("explicitAuthorCorrections");
        assertThat(corrections.size()).isEqualTo(1); assertThat(corrections.get(0).path("targetId").asText()).isEqualTo(world.getId().toString());
        assertThat(corrections.get(0).path("beforeValue").asText()).isEqualTo("북부");
        assertThat(corrections.get(0).path("afterValue").asText()).isEqualTo("남부"); noExtraction();
    }
    @Test void characterWholePutCannotOverwriteOtherSlots() throws Exception {
        var first=start(); var request=correction(first,"characters","unsafe");
        request.put("character",Map.of("targetId",character.getId(),"expectedSnapshotVersion",character.getSnapshotVersion(),"factType","AGE","factKey","age","edit",characterEdit(21,99)));
        conflict(correctionPath(first),request,"VALIDATION_CORRECTION_CONFLICT");
        em.refresh(character); assertThat(character.getCurrentAge()).isEqualTo(20); assertThat(character.getCurrentLevel()).isEqualTo(1);
        assertThat(valid(first)).isTrue(); assertThat(em.createQuery("select count(c) from ValidationCorrection c",Long.class).getSingleResult()).isZero(); noExtraction();
    }
    @Test void correctionKeyCannotBeReusedForDifferentEdit() throws Exception {
        var first=start();var input=correction(first,"worldSettings","same-key");input.put("worldSetting",worldCommand(world.getVersion()));
        postOk(correctionPath(first),input);input.put("expectedEntryHash","f".repeat(64));
        conflict(correctionPath(first),input,"VALIDATION_IDEMPOTENCY_CONFLICT");assertThat(world.getPropertyValue("서식지")).isEqualTo("남부");noExtraction();
    }
    @Test void characterCorrectionUsesActualManualEditAndPreservesOtherSlots() throws Exception {
        var first=start(); var request=correction(first,"characters","character-fix");
        request.put("character",Map.of("targetId",character.getId(),"expectedSnapshotVersion",character.getSnapshotVersion(),"factType","AGE","factKey","age","edit",characterEdit(21,1)));
        var receipt=postOk(correctionPath(first),request); assertThat(character.getCurrentAge()).isEqualTo(21); assertThat(character.getCurrentLevel()).isEqualTo(1);
        assertThat(receipt.path("entityVersion").asLong()).isGreaterThan(0);
        assertThat(em.createQuery("select count(f) from CharacterFact f",Long.class).getSingleResult()).isEqualTo(1); noExtraction();
    }
    @Test void ordinarySaveDoesNotCreateCorrection() throws Exception {
        var first=start();
        mvc.perform(patch("/api/v1/works/"+work.getId()+"/characters/"+character.getId()).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(characterEdit(21,1))))
                .andExpect(status().isOk());
        assertThat(valid(first)).as("an ordinary save invalidates the report").isFalse();
        assertThat(em.createQuery("select count(c) from ValidationCorrection c",Long.class).getSingleResult()).isZero(); noExtraction();
    }
    @Test void ambiguousOrHistoricalCorrectionRejected() throws Exception {
        var first=start(); var request=correction(first,"worldSettings","wrong-subject");
        request.put("worldSetting",worldCommand(world.getVersion())); request.put("entryRef","B999");
        conflict(correctionPath(first),request,"VALIDATION_CORRECTION_CONFLICT");
        // #381: 대상은 id로 찾지만 상세가 준 참조가 그 대상의 지금 색인 참조와 달라도 거절한다(다른 주체의 참조 B1).
        request.put("entryRef","B1");conflict(correctionPath(first),request,"VALIDATION_CORRECTION_CONFLICT");
        assertThat(world.getPropertyValue("서식지")).isEqualTo("북부"); assertThat(valid(first)).isTrue(); noExtraction();
    }
    @Test void staleCorrectionRejectsAllEdits() throws Exception {
        var first=start(); var request=correction(first,"worldSettings","stale-fix"); request.put("worldSetting",worldCommand(world.getVersion()+1));
        conflict(correctionPath(first),request,"VALIDATION_CORRECTION_CONFLICT");
        assertThat(world.getPropertyValue("서식지")).isEqualTo("북부"); assertThat(valid(first)).isTrue(); noExtraction();
    }
    @Test void priorSourcePurgeDeletesCorrectionCopiesAfterReportRetirement() throws Exception {
        var first=start();var input=correction(first,"worldSettings","before-purge");input.put("worldSetting",worldCommand(world.getVersion()));
        postOk(correctionPath(first),input);var next=postOk(recheckPath(first),recheck(first,"new-baseline"));
        states.purgeSourceEvidenceForWorkForUpdate(work.getId(),prior.getId(),100);em.flush();
        assertThat(valid(next)).as("the purge invalidates the new report").isFalse();
        assertThat(em.createQuery("select count(c) from ValidationCorrection c",Long.class).getSingleResult()).isZero();noExtraction();
    }
    @Test void historicalClaimCannotBeEditedAsTheCurrentBaseline() throws Exception {
        var job=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.create(work,null,prior,org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION);em.persist(job);job.succeed(null,0,0);
        var history=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(work,prior,job,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,"합성 종족","서식지","옛 서식지",com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode(),java.math.BigDecimal.ONE,null);
        org.springframework.test.util.ReflectionTestUtils.setField(history,"reviewStatus",org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.CONFIRMED);
        org.springframework.test.util.ReflectionTestUtils.setField(history,"historyOnly",true);em.persist(history);em.flush();
        var first=start();
        // #381: 현재 확정 설정에는 이력 행이 없다(인물·세계관 주체 둘뿐). 이력 값(옛 서식지)을 담은 행의 hash로는 현재값을 정정할 수 없다.
        assertThat(liveSettings.index(work.getId()).subjects()).extracting(x->x.domain()).containsExactly("characters","worldSettings");
        var input=correction(first,"worldSettings","history-only");input.put("worldSetting",worldCommand(world.getVersion()));
        var historical=liveWorld().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)historical.path("state").path("propertiesJson")).put("서식지","옛 서식지");
        input.put("expectedEntryHash",journal.hash(historical));
        conflict(correctionPath(first),input,"VALIDATION_CORRECTION_CONFLICT");
        assertThat(world.getPropertyValue("서식지")).isEqualTo("북부");assertThat(valid(first)).isTrue();
        assertThat(em.createQuery("select count(c) from ValidationCorrection c",Long.class).getSingleResult()).isZero();
        assertThat(em.createQuery("select count(j) from AnalysisJob j",Long.class).getSingleResult()).isEqualTo(1);
        assertThat(em.createQuery("select count(c) from WorldSettingCandidate c",Long.class).getSingleResult()).isEqualTo(1);
    }

    @Test @org.junit.jupiter.api.DisplayName("#381 legacy(seed) report의 정정은 저장 기준 항목 CAS와 고정 slot 비교로 그대로 성공한다")
    void legacySeedReportCorrectionKeepsTheStoredBaselinePath() throws Exception {
        var first=start();toLegacySeed(stored(first));var staging=get(java.util.UUID.fromString(first.path("reportId").asText()));
        var request=correction(staging,"worldSettings","legacy-fix");request.put("worldSetting",worldCommand(world.getVersion()));
        assertThat(request.get("expectedEntryHash")).as("the stored entry, not the live row").isEqualTo(journal.hash(stored(staging).getBaselineEntries().get(1)));
        var receipt=postOk(correctionPath(staging),request);
        assertThat(world.getPropertyValue("서식지")).isEqualTo("남부");assertThat(receipt.path("correctionId").isTextual()).isTrue();assertThat(valid(staging)).isFalse();
        noExtraction();
    }

    static Stream<Arguments> canonicalWorldPaths() {
        return Stream.of(
                Arguments.of(null,"서식지",null," 서식지 ",null," 생활 지역 ",null,"생활 지역"),
                Arguments.of(null,"Habitat",null," habitat ",null," Climate ",null,"Climate"),
                Arguments.of(null,"Café",null," Cafe\u0301 ",null," Re\u0301gion ",null,"Région"),
                Arguments.of(null,"서식지",null," 서식지 "," 1층 "," 생활 지역 ","1층","생활 지역"),
                Arguments.of("Region","Habitat"," region "," habitat "," Zone "," Range ","Zone","Range"));
    }
    @ParameterizedTest @MethodSource("canonicalWorldPaths")
    void diagnosticCorrectionUsesActualCanonicalStoredWorldPaths(String oldScope,String oldName,String requestedOldScope,String requestedOldName,
            String requestedNewScope,String requestedNewName,String expectedScope,String expectedName) throws Exception {
        world.updateProperty(null,"서식지",oldScope,oldName,"북부");em.flush();var first=start();
        var job=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.create(work,null,target,org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION);
        job.configureReviewMode(org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.AUTOMATIC);states.initializeRun(List.of(job));
        job.claim("synthetic-no-llm","합성 완료",java.time.LocalDateTime.now().plusMinutes(1));assertThat(states.prepareInput(job)).isTrue();
        states.seal(job);job.completeAutomaticApplication();job.succeed(null,0,0);states.confirmedSettingsChanged(work.getId());em.flush();
        var diagnostic=postOk(recheckPath(first),recheck(first,"path-diagnostic"));
        Map<String,Object> edit=new LinkedHashMap<>();edit.put("currentScopeName",requestedOldScope);edit.put("currentSettingName",requestedOldName);
        edit.put("scopeName",requestedNewScope);edit.put("settingName",requestedNewName);edit.put("settingValue","남부");edit.put("version",world.getVersion());
        var input=correction(diagnostic,"worldSettings","canonical-correction");input.put("worldSetting",Map.of("targetId",world.getId(),"expectedVersion",world.getVersion(),"edit",edit));
        var receipt=postOk(correctionPath(diagnostic),input);
        var storedCorrection=em.find(org.monitoring.catchholebackend.domain.validation.entity.ValidationCorrection.class,UUID.fromString(receipt.path("correctionId").asText()));
        var scope=storedCorrection.getScope();
        assertThat(scope.path("currentScopeName").isNull()?null:scope.path("currentScopeName").asText()).isEqualTo(oldScope);
        assertThat(scope.path("currentSettingName").asText()).isEqualTo(oldName);
        assertThat(scope.path("scopeName").isNull()?null:scope.path("scopeName").asText()).isEqualTo(expectedScope);
        assertThat(scope.path("settingName").asText()).isEqualTo(expectedName);
        assertThat(scope.path("beforeValue").asText()).isEqualTo("북부");assertThat(scope.path("afterValue").asText()).isEqualTo("남부");
        var command=recheck(diagnostic,"canonical-recheck");command.remove("expectedBaselineHash");command.put("correctionReceiptId",receipt.path("correctionId").asText());
        var next=postOk(recheckPath(diagnostic),command);
        assertThat(next.path("baselineStatus").asText()).isEqualTo("READY");
        assertThat(liveWorld().path("state").path("propertiesJson")).isEqualTo(world.getPropertiesJson());
        assertThat(world.getPropertyValue(expectedScope,expectedName)).isEqualTo("남부");
        assertThat(em.createQuery("select count(j) from AnalysisJob j",Long.class).getSingleResult()).isEqualTo(1);
        assertThat(em.createQuery("select count(c) from WorldSettingCandidate c",Long.class).getSingleResult()).isZero();
    }
}
