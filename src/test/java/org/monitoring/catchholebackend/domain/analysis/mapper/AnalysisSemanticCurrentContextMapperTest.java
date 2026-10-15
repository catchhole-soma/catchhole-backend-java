package org.monitoring.catchholebackend.domain.analysis.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.global.exception.AppException;

@DisplayName("claim 전용 고정 CURRENT 완전성·직렬화 경계")
class AnalysisSemanticCurrentContextMapperTest {
    private final AnalysisStateJournal journal = new AnalysisStateJournal();
    private final tools.jackson.databind.ObjectMapper wire = new tools.jackson.databind.ObjectMapper();
    private final AnalysisSemanticCurrentContextMapper mapper = new AnalysisSemanticCurrentContextMapper(wire, journal);
    private static final UUID CHARACTER = UUID.fromString("3a54feaa-070d-49e7-a5b0-0bb4cde8b8ce");

    @Test @DisplayName("모든 character 슬롯과 world 속성을 합산한 정확한 4096 상한은 허용한다")
    void acceptsExactly4096AggregateItems() {
        ObjectNode state = state(2048, 2048);
        var payload = mapper.toResponse(job(state), state);
        assertThat(payload.characters().getFirst().currentSlots()).hasSize(2048);
        assertThat(payload.worldSubjects().getFirst().properties()).hasSize(2048);
        state.withObject("worldSettings").elements().next().withObject("propertiesJson").put("overflow", "one more");
        assertThatThrownBy(() -> mapper.toResponse(job(state), state)).isInstanceOf(AnalysisSemanticCurrentContextMapper.InvalidCarrierException.class);
    }

    @Test @DisplayName("전체 실제 직렬화의 정확한 1MiB는 허용하며 한 바이트 더 큰 문맥은 거절한다")
    void acceptsExactSerializedByteLimitAndRejectsOneMore() {
        ObjectNode state = state(1, 0);
        ObjectNode slot = (ObjectNode) state.path("characters").elements().next().path("slots").elements().next();
        slot.put("factValue", "");
        int baseBytes = wire.writeValueAsBytes(mapper.toResponse(job(state), state)).length;
        slot.put("factValue", "x".repeat(AnalysisSemanticCurrentContextMapper.MAX_UTF8_BYTES - baseBytes));
        assertThat(wire.writeValueAsBytes(mapper.toResponse(job(state), state))).hasSize(AnalysisSemanticCurrentContextMapper.MAX_UTF8_BYTES);
        slot.put("factValue", slot.path("factValue").asText() + "x");
        assertThatThrownBy(() -> mapper.toResponse(job(state), state)).isInstanceOf(AnalysisSemanticCurrentContextMapper.InvalidCarrierException.class);
    }

    @Test @DisplayName("frozen JSON 소수와 큰 정수는 plain wire 값으로 옮길 때 정밀도를 잃지 않는다")
    void preservesFrozenJsonNumericPrecision() {
        ObjectNode state = state(1, 0);
        ObjectNode slot = (ObjectNode) state.path("characters").elements().next().path("slots").elements().next();
        BigDecimal amount = new BigDecimal("0.12345678901234567890123456789");
        var values = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("amount", amount)
                .put("large", new java.math.BigInteger("123456789012345678901234567890"));
        slot.set("valueJson", values);
        var payload = mapper.toResponse(job(state), state);
        Map<?,?> actual = (Map<?,?>) payload.characters().getFirst().currentSlots().getFirst().valueJson();
        assertThat(actual.get("amount")).isEqualTo(amount);
        assertThat(actual.get("large")).isEqualTo(new java.math.BigInteger("123456789012345678901234567890"));
        assertThat(wire.writeValueAsString(payload)).contains(amount.toPlainString(), "123456789012345678901234567890");
    }

    @ParameterizedTest @ValueSource(strings={"MISSING_ID_KEY", "MISSING_SLOTS", "UNKNOWN_WORLD_CATEGORY", "BAD_PROVISIONAL", "WORLD_OWNER_XOR", "WORLD_WRONG_REF", "NULL_SCOPE_CHILD", "NESTED_WORLD_VALUE"})
    @DisplayName("누락·틀린 identity·category·중첩 세계관 값은 complete 문맥으로 전달하지 않는다")
    void rejectsMalformedFrozenOwners(String defect) {
        ObjectNode state = state(1,1);
        ObjectNode character = (ObjectNode)state.path("characters").elements().next();
        ObjectNode world = (ObjectNode)state.path("worldSettings").elements().next();
        switch(defect) {
            case "MISSING_ID_KEY" -> character.remove("provisionalSubjectKey");
            case "MISSING_SLOTS" -> character.remove("slots");
            case "UNKNOWN_WORLD_CATEGORY" -> world.put("category","NEW_CATEGORY");
            case "BAD_PROVISIONAL" -> character.putNull("actualCharacterId").put("provisionalSubjectKey","unknown-ref");
            case "WORLD_OWNER_XOR" -> world.put("provisionalSubjectKey","provisional-world:"+UUID.randomUUID());
            case "WORLD_WRONG_REF" -> world.put("actualWorldSettingId",UUID.randomUUID().toString());
            case "NULL_SCOPE_CHILD" -> world.withObject("propertiesJson").putObject("scope").putNull("leaf");
            case "NESTED_WORLD_VALUE" -> world.withObject("propertiesJson").putObject("scope").putObject("leaf").put("value","nested");
            default -> throw new AssertionError(defect);
        }
        assertThatThrownBy(() -> mapper.toResponse(job(state), state)).isInstanceOf(AnalysisSemanticCurrentContextMapper.InvalidCarrierException.class);
    }

    @Test @DisplayName("input hash 충돌은 carrier 오류로 바꾸지 않고 기존 누적 실행 충돌을 유지한다")
    void hashConflictIsNotDeterministicCarrierFailure() {
        ObjectNode state = state(1,1); var job=job(state);
        state.withObject("worldSettings").elements().next().withObject("propertiesJson").put("k0","changed");
        assertThatThrownBy(() -> mapper.toResponse(job,state)).isInstanceOfSatisfying(AppException.class,
                error -> assertThat(error.getResultCode()).isEqualTo(AnalysisJobErrorCode.ANALYSIS_RUN_STATE_CONFLICT));
    }

    private AnalysisJob job(ObjectNode input) {
        var job = mock(AnalysisJob.class);
        when(job.isOrderedProvisional()).thenReturn(true); when(job.hasCurrentSourceVersion()).thenReturn(true);
        when(job.getAnalysisRunId()).thenReturn(UUID.fromString("d6c12376-27a4-4b96-9a5e-5bc9e8f49519"));
        when(job.getRunGeneration()).thenReturn(1L); when(job.getInputStateHash()).thenReturn(journal.hash(input));
        when(job.getSourceContentHash()).thenReturn("d".repeat(64));
        return job;
    }

    private ObjectNode state(int slots,int properties) {
        ObjectNode state = journal.emptyState();
        if(slots>0) {
            var owner=state.withObject("characters").putObject("character:"+CHARACTER);
            owner.put("actualCharacterId",CHARACTER.toString()).putNull("provisionalSubjectKey").put("name","하린");
            var current=owner.putObject("slots");
            for(int i=0;i<slots;i++) current.putObject("PROFILE:k"+i).put("factType","PROFILE").put("factKey","k"+i).putNull("factValue").putNull("valueJson");
        }
        if(properties>0) {
            var id=UUID.fromString("f86f71aa-2b02-4d65-9d64-73b49e067244");
            var owner=state.withObject("worldSettings").putObject("world:"+id);
            owner.put("actualWorldSettingId",id.toString()).putNull("provisionalSubjectKey").put("category","LOCATION").put("subjectName","북문");
            var current=owner.putObject("propertiesJson"); for(int i=0;i<properties;i++) current.put("k"+i,"value");
        }
        return state;
    }
}
