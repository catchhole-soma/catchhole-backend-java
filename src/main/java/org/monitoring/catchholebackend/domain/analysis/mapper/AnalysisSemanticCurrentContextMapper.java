package org.monitoring.catchholebackend.domain.analysis.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerSemanticCurrentContextPayload;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingNameNormalizer;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;

/** 전체 고정 CURRENT의 완전성·identity·상한을 한 번 검증하며 live 값을 보충하지 않는다. */
@Component
@RequiredArgsConstructor
public class AnalysisSemanticCurrentContextMapper {
    public static final int MAX_ITEMS = 4096;
    public static final int MAX_UTF8_BYTES = 1_048_576;
    private final tools.jackson.databind.ObjectMapper wireMapper;
    private final AnalysisStateJournal journal;
    private final com.fasterxml.jackson.databind.ObjectMapper jsonValues = new com.fasterxml.jackson.databind.ObjectMapper();

    public WorkerSemanticCurrentContextPayload toResponse(AnalysisJob job, JsonNode input) {
        // 실행·원문·hash 충돌은 deterministic carrier 오류로 바꾸지 않는다.
        if (!job.isOrderedProvisional() || !job.hasCurrentSourceVersion() || input == null || !input.isObject()
                || job.getAnalysisRunId() == null || job.getRunGeneration() == null || job.getRunGeneration() < 1
                || !hash(job.getInputStateHash()) || !hash(job.getSourceContentHash())
                || !job.getInputStateHash().equals(journal.hash(input))) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_RUN_STATE_CONFLICT);
        }
        JsonNode characters = object(input, "characters");
        JsonNode worlds = object(input, "worldSettings");
        List<WorkerSemanticCurrentContextPayload.Character> resultCharacters = new ArrayList<>();
        List<WorkerSemanticCurrentContextPayload.WorldSubject> resultWorlds = new ArrayList<>();
        Set<String> characterOwners = new HashSet<>(), worldOwners = new HashSet<>();
        int count = 0;
        for (String ref : keys(characters)) {
            JsonNode owner = characters.get(ref);
            Identity identity = identity(owner, ref, "actualCharacterId", "character:", "provisional-character:");
            if (!characterOwners.add(identity.key())) throw invalid();
            text(owner, "name", false);
            JsonNode slots = object(owner, "slots");
            List<WorkerSemanticCurrentContextPayload.CurrentSlot> resultSlots = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String slotRef : keys(slots)) {
                JsonNode slot = slots.get(slotRef);
                CharacterFactType type = enumValue(CharacterFactType.class, text(slot, "factType", false));
                String key = text(slot, "factKey", false);
                String expected = type.name() + ":" + key;
                if (!slotRef.equals(expected) || !seen.add(expected)) throw invalid();
                String value = text(slot, "factValue", true);
                JsonNode valueJson = required(slot, "valueJson");
                requireJson(valueJson);
                if (++count > MAX_ITEMS) throw invalid();
                // Jackson 2 tree는 MVC의 Jackson 3에서 bean으로 해석될 수 있으므로 plain JSON 값으로 복사한다.
                resultSlots.add(new WorkerSemanticCurrentContextPayload.CurrentSlot(type, key, value,
                        jsonValues.convertValue(valueJson, Object.class)));
            }
            resultCharacters.add(new WorkerSemanticCurrentContextPayload.Character(identity.actual(), identity.provisional(), List.copyOf(resultSlots)));
        }
        for (String ref : keys(worlds)) {
            JsonNode owner = worlds.get(ref);
            Identity identity = identity(owner, ref, "actualWorldSettingId", "world:", "provisional-world:");
            if (!worldOwners.add(identity.key())) throw invalid();
            WorldSettingCategory category = enumValue(WorldSettingCategory.class, text(owner, "category", false));
            String name = text(owner, "subjectName", false);
            JsonNode values = object(owner, "propertiesJson");
            List<WorkerSemanticCurrentContextPayload.Property> properties = new ArrayList<>();
            Set<List<String>> paths = new HashSet<>();
            for (String root : keys(values)) {
                if (root.isBlank()) throw invalid();
                JsonNode value = values.get(root);
                if (value.isObject()) {
                    for (String leaf : keys(value)) {
                        if (++count > MAX_ITEMS) throw invalid();
                        addProperty(properties, paths, root, leaf, value.get(leaf));
                    }
                } else {
                    if (++count > MAX_ITEMS) throw invalid();
                    addProperty(properties, paths, null, root, value);
                }
            }
            resultWorlds.add(new WorkerSemanticCurrentContextPayload.WorldSubject(identity.actual(), identity.provisional(),
                    category, name, List.copyOf(properties)));
        }
        WorkerSemanticCurrentContextPayload result = new WorkerSemanticCurrentContextPayload(1, job.getAnalysisRunId(),
                job.getRunGeneration(), job.getInputStateHash(), job.getSourceContentHash(), true,
                List.copyOf(resultCharacters), List.copyOf(resultWorlds));
        if (wireMapper.writeValueAsBytes(result).length > MAX_UTF8_BYTES) throw invalid();
        return result;
    }

    private void addProperty(List<WorkerSemanticCurrentContextPayload.Property> properties, Set<List<String>> paths,
            String scope, String name, JsonNode value) {
        if (name.isBlank() || value == null || !value.isTextual()) throw invalid();
        // List.of는 null을 허용하지 않는다. root와 실제 scope는 서로 다른 전체 경로다.
        List<String> path = java.util.Arrays.asList(WorldSettingNameNormalizer.duplicateKey(scope), WorldSettingNameNormalizer.duplicateKey(name));
        if (!paths.add(path)) throw invalid();
        properties.add(new WorkerSemanticCurrentContextPayload.Property(scope, name, value.textValue()));
    }

    private Identity identity(JsonNode owner, String ref, String actualField, String actualPrefix, String provisionalPrefix) {
        String actual = text(owner, actualField, true), provisional = text(owner, "provisionalSubjectKey", true);
        if ((actual == null) == (provisional == null)) throw invalid();
        UUID id = actual == null ? null : uuid(actual);
        String expected = actual == null ? provisional : actualPrefix + id;
        if (provisional != null && (!provisional.startsWith(provisionalPrefix)
                || !provisional.equals(provisionalPrefix + uuid(provisional.substring(provisionalPrefix.length()))))) throw invalid();
        if (!ref.equals(expected)) throw invalid();
        return new Identity(id, provisional, expected);
    }

    private UUID uuid(String value) {
        try { return UUID.fromString(value); } catch (IllegalArgumentException exception) { throw invalid(); }
    }

    private JsonNode required(JsonNode owner, String field) {
        if (owner == null || !owner.isObject() || !owner.has(field)) throw invalid();
        return owner.get(field);
    }

    private JsonNode object(JsonNode owner, String field) {
        JsonNode value = required(owner, field);
        if (!value.isObject()) throw invalid();
        return value;
    }

    private String text(JsonNode owner, String field, boolean nullable) {
        JsonNode value = required(owner, field);
        if (nullable && value.isNull()) return null;
        if (!value.isTextual() || (!nullable && value.textValue().isBlank())) throw invalid();
        return value.textValue();
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        try { return Enum.valueOf(type, value); } catch (IllegalArgumentException exception) { throw invalid(); }
    }

    private List<String> keys(JsonNode object) {
        List<String> keys = new ArrayList<>(); object.fieldNames().forEachRemaining(keys::add); keys.sort(String::compareTo); return keys;
    }

    private void requireJson(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isPojo() || value.isBinary()
                || value.isFloatingPointNumber() && !Double.isFinite(value.doubleValue())) throw invalid();
        value.forEach(this::requireJson);
    }

    private boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private InvalidCarrierException invalid() { return new InvalidCarrierException(); }
    private record Identity(UUID actual, String provisional, String key) {}

    /** Source/run/hash AppException과 분리된, 입력 자체의 명시적 중단 신호다. */
    public static final class InvalidCarrierException extends RuntimeException {
        public InvalidCarrierException() { super("SEMANTIC_CURRENT_CONTEXT_INVALID: 고정 현재 설정의 형식 또는 상한이 올바르지 않습니다."); }
    }
}
