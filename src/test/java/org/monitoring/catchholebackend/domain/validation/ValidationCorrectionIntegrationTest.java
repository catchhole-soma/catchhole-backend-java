package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties="spring.config.import=") @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class ValidationCorrectionIntegrationTest extends ValidationRecheckFixture {
    @org.springframework.beans.factory.annotation.Autowired org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService states;
    Map<String,Object> worldCommand(long version) {
        return Map.of("targetId",world.getId(),"expectedVersion",version,"edit",Map.of("currentSettingName","서식지","settingName","서식지","settingValue","남부","version",version));
    }
    @Test void baselineCorrectionIsAtomicWithActualEdit() throws Exception {
        var first=start(); var request=correction(first,"worldSettings","fix"); request.put("worldSetting",worldCommand(world.getVersion()));
        var receipt=postOk(correctionPath(first),request);
        assertThat(world.getPropertyValue("서식지")).isEqualTo("남부"); assertThat(stored(first).getBaselineSeed()).isNull();
        assertThat(receipt.path("correctionId").isTextual()).isTrue();
        var retry=postOk(correctionPath(first),request); assertThat(retry).isEqualTo(receipt);
        var recheck=recheck(first,"corrected"); recheck.remove("expectedBaselineHash"); recheck.put("correctionReceiptId",receipt.path("correctionId").asText());
        var next=postOk(recheckPath(first),recheck);
        assertThat(stored(next).getBaselineSeed().path("worldSettings").toString()).contains("남부");
        var corrections=stored(next).getBaselineSeed().path("explicitAuthorCorrections");
        assertThat(corrections.size()).isEqualTo(1); assertThat(corrections.get(0).path("targetId").asText()).isEqualTo(world.getId().toString());
        assertThat(corrections.get(0).path("beforeValue").asText()).isEqualTo("북부");
        assertThat(corrections.get(0).path("afterValue").asText()).isEqualTo("남부"); noExtraction();
    }
    @Test void characterWholePutCannotOverwriteOtherSlots() throws Exception {
        var first=start(); var request=correction(first,"characters","unsafe");
        request.put("character",Map.of("targetId",character.getId(),"expectedSnapshotVersion",character.getSnapshotVersion(),"factType","AGE","factKey","age","edit",characterEdit(21,99)));
        conflict(correctionPath(first),request,"VALIDATION_CORRECTION_CONFLICT");
        em.refresh(character); assertThat(character.getCurrentAge()).isEqualTo(20); assertThat(character.getCurrentLevel()).isEqualTo(1);
        assertThat(stored(first).getBaselineSeed()).isNotNull(); assertThat(em.createQuery("select count(c) from ValidationCorrection c",Long.class).getSingleResult()).isZero(); noExtraction();
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
        assertThat(stored(first).getBaselineSeed()).isNull();
        assertThat(em.createQuery("select count(c) from ValidationCorrection c",Long.class).getSingleResult()).isZero(); noExtraction();
    }
    @Test void ambiguousOrHistoricalCorrectionRejected() throws Exception {
        var first=start(); var request=correction(first,"worldSettings","wrong-subject");
        request.put("worldSetting",worldCommand(world.getVersion())); request.put("entryRef","B999");
        conflict(correctionPath(first),request,"VALIDATION_CORRECTION_CONFLICT");
        assertThat(world.getPropertyValue("서식지")).isEqualTo("북부"); assertThat(stored(first).getBaselineSeed()).isNotNull(); noExtraction();
    }
    @Test void staleCorrectionRejectsAllEdits() throws Exception {
        var first=start(); var request=correction(first,"worldSettings","stale-fix"); request.put("worldSetting",worldCommand(world.getVersion()+1));
        conflict(correctionPath(first),request,"VALIDATION_CORRECTION_CONFLICT");
        assertThat(world.getPropertyValue("서식지")).isEqualTo("북부"); assertThat(stored(first).getBaselineSeed()).isNotNull(); noExtraction();
    }
    @Test void priorSourcePurgeDeletesCorrectionCopiesAfterReportRetirement() throws Exception {
        var first=start();var input=correction(first,"worldSettings","before-purge");input.put("worldSetting",worldCommand(world.getVersion()));
        postOk(correctionPath(first),input);var next=postOk(recheckPath(first),recheck(first,"new-baseline"));
        states.purgeSourceEvidenceForWorkForUpdate(work.getId(),prior.getId(),100);em.flush();
        assertThat(stored(next).getBaselineSeed()).isNull();
        assertThat(em.createQuery("select count(c) from ValidationCorrection c",Long.class).getSingleResult()).isZero();noExtraction();
    }
    @Test void historicalClaimCannotBeEditedAsTheCurrentBaseline() throws Exception {
        var job=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.create(work,null,prior,org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION);em.persist(job);job.succeed(null,0,0);
        var history=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(work,prior,job,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,"합성 종족","서식지","옛 서식지",com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode(),java.math.BigDecimal.ONE,null);
        org.springframework.test.util.ReflectionTestUtils.setField(history,"reviewStatus",org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.CONFIRMED);
        org.springframework.test.util.ReflectionTestUtils.setField(history,"historyOnly",true);em.persist(history);em.flush();
        var first=start();var input=correction(first,"worldHistory","history-only");input.put("worldSetting",worldCommand(world.getVersion()));
        conflict(correctionPath(first),input,"VALIDATION_CORRECTION_CONFLICT");
        assertThat(world.getPropertyValue("서식지")).isEqualTo("북부");assertThat(stored(first).getBaselineSeed()).isNotNull();
        assertThat(em.createQuery("select count(c) from ValidationCorrection c",Long.class).getSingleResult()).isZero();
        assertThat(em.createQuery("select count(j) from AnalysisJob j",Long.class).getSingleResult()).isEqualTo(1);
        assertThat(em.createQuery("select count(c) from WorldSettingCandidate c",Long.class).getSingleResult()).isEqualTo(1);
    }
}
