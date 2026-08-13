package org.monitoring.catchholebackend.domain.character.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;

/** WorkCharacter의 authoritative snapshot을 slot map으로 읽고 다시 저장한다. */
@Component
public class CharacterSnapshotAccessor {

    private static final String INTERNAL_ENVELOPE_KEY = "__catchhole_snapshot";
    private static final String INTERNAL_FORMAT_KEY = "format";
    private static final String INTERNAL_FORMAT_SENTINEL = "character-snapshot-entry";
    private static final String INTERNAL_VERSION_KEY = "version";
    private static final String INTERNAL_FACT_VALUE_KEY = "factValue";
    private static final String INTERNAL_VALUE_JSON_KEY = "valueJson";
    private static final int INTERNAL_FORMAT_VERSION = 1;

    public Map<CharacterSnapshotSlot, CharacterSnapshotEntry> read(WorkCharacter character) {
        Map<CharacterSnapshotSlot, CharacterSnapshotEntry> entries = new LinkedHashMap<>();
        putInteger(entries, CharacterFactType.AGE, "age", character.getCurrentAge());
        putInteger(entries, CharacterFactType.LEVEL, "level", character.getCurrentLevel());
        putGroup(entries, CharacterFactType.PROFILE, character.getProfileJson());
        putGroup(entries, CharacterFactType.STAT, character.getStatsJson());
        putGroup(entries, CharacterFactType.SKILL, character.getSkillsJson());
        putGroup(entries, CharacterFactType.ITEM, character.getItemsJson());
        putStatusGroup(entries, character.getStatusesJson());
        return entries;
    }

    /**
     * 내부 envelope 도입 전 raw snapshot은 factValue를 따로 보관하지 않았다. 해당 slot의
     * provenance 마지막 Fact를 호환 표시값으로 사용하되 raw valueJson은 snapshot 값을 유지한다.
     */
    public Map<CharacterSnapshotSlot, CharacterSnapshotEntry> read(
            WorkCharacter character,
            Map<CharacterSnapshotSlot, List<CharacterFact>> sourceFactsBySlot
    ) {
        Map<CharacterSnapshotSlot, CharacterSnapshotEntry> entries = read(character);
        entries.replaceAll((slot, entry) -> {
            if (entry.factValuePersisted()) {
                return entry;
            }
            List<CharacterFact> sourceFacts = sourceFactsBySlot.getOrDefault(slot, List.of());
            if (sourceFacts.isEmpty()) {
                return entry;
            }
            return new CharacterSnapshotEntry(
                    slot,
                    sourceFacts.getLast().getFactValue(),
                    entry.valueJson(),
                    false
            );
        });
        return entries;
    }

    public void replace(
            WorkCharacter character,
            Map<CharacterSnapshotSlot, CharacterSnapshotEntry> entries
    ) {
        replace(character, entries, false);
    }

    public void replace(
            WorkCharacter character,
            Map<CharacterSnapshotSlot, CharacterSnapshotEntry> entries,
            boolean provenanceChanged
    ) {
        Integer currentAge = null;
        Integer currentLevel = null;
        ObjectNode profile = JsonNodeFactory.instance.objectNode();
        ObjectNode stats = JsonNodeFactory.instance.objectNode();
        ObjectNode skills = JsonNodeFactory.instance.objectNode();
        ObjectNode items = JsonNodeFactory.instance.objectNode();
        ObjectNode statuses = JsonNodeFactory.instance.objectNode();

        for (CharacterSnapshotEntry entry : entries.values()) {
            CharacterSnapshotSlot slot = entry.slot();
            switch (slot.factType()) {
                case AGE -> currentAge = resolveNonNegativeInteger(entry);
                case LEVEL -> currentLevel = resolveNonNegativeInteger(entry);
                case PROFILE -> profile.set(slot.factKey(), toStoredEntry(entry));
                case STAT -> stats.set(slot.factKey(), toStoredEntry(entry));
                case SKILL -> skills.set(slot.factKey(), toStoredEntry(entry));
                case ITEM -> items.set(slot.factKey(), toStoredEntry(entry));
                case STATUS, TIME -> statuses.set(slot.factKey(), toStoredEntry(entry));
            }
        }
        character.replaceCurrentSnapshots(
                currentAge,
                currentLevel,
                nullIfEmpty(profile),
                nullIfEmpty(stats),
                nullIfEmpty(skills),
                nullIfEmpty(items),
                nullIfEmpty(statuses),
                provenanceChanged
        );
    }

    public CharacterSnapshotEntry entry(
            CharacterFactType factType,
            String factKey,
            String factValue,
            JsonNode valueJson
    ) {
        return new CharacterSnapshotEntry(
                new CharacterSnapshotSlot(factType, factKey),
                factValue,
                valueJson,
                true
        );
    }

    private void putInteger(
            Map<CharacterSnapshotSlot, CharacterSnapshotEntry> entries,
            CharacterFactType factType,
            String factKey,
            Integer value
    ) {
        if (value == null) {
            return;
        }
        ObjectNode valueJson = JsonNodeFactory.instance.objectNode().put("value", value);
        put(entries, factType, factKey, value.toString(), valueJson);
    }

    private void putGroup(
            Map<CharacterSnapshotSlot, CharacterSnapshotEntry> entries,
            CharacterFactType factType,
            JsonNode group
    ) {
        if (group == null || !group.isObject()) {
            return;
        }
        group.properties().forEach(entry -> putStoredEntry(entries, factType, entry.getKey(), entry.getValue()));
    }

    private void putStatusGroup(
            Map<CharacterSnapshotSlot, CharacterSnapshotEntry> entries,
            JsonNode group
    ) {
        if (group == null || !group.isObject()) {
            return;
        }
        group.properties().forEach(entry -> {
            CharacterFactType factType = entry.getKey().startsWith("time.")
                    ? CharacterFactType.TIME
                    : CharacterFactType.STATUS;
            putStoredEntry(entries, factType, entry.getKey(), entry.getValue());
        });
    }

    /**
     * 신규 snapshot entry는 사용자 valueJson과 표시용 factValue를 내부 envelope에 함께 저장한다.
     * 기존 DB의 raw valueJson도 그대로 읽어 점진적으로 새 형식으로 전환한다.
     */
    private void putStoredEntry(
            Map<CharacterSnapshotSlot, CharacterSnapshotEntry> entries,
            CharacterFactType factType,
            String factKey,
            JsonNode storedEntry
    ) {
        JsonNode envelope = resolveEnvelope(storedEntry);
        if (envelope == null) {
            put(entries, factType, factKey, toFactValue(storedEntry), storedEntry, false);
            return;
        }
        JsonNode factValueNode = envelope.get(INTERNAL_FACT_VALUE_KEY);
        JsonNode valueJson = envelope.get(INTERNAL_VALUE_JSON_KEY);
        put(
                entries,
                factType,
                factKey,
                factValueNode == null || factValueNode.isNull() ? null : factValueNode.asText(),
                valueJson == null || valueJson.isNull() ? null : valueJson,
                true
        );
    }

    private JsonNode toStoredEntry(CharacterSnapshotEntry entry) {
        // 의미상 변경되지 않은 legacy raw entry는 자동 변환하지 않아 snapshot version도 유지한다.
        if (!entry.factValuePersisted()) {
            return entry.valueJson() == null ? JsonNodeFactory.instance.nullNode() : entry.valueJson();
        }
        ObjectNode envelope = JsonNodeFactory.instance.objectNode();
        envelope.put(INTERNAL_FORMAT_KEY, INTERNAL_FORMAT_SENTINEL);
        envelope.put(INTERNAL_VERSION_KEY, INTERNAL_FORMAT_VERSION);
        if (entry.factValue() == null) {
            envelope.putNull(INTERNAL_FACT_VALUE_KEY);
        } else {
            envelope.put(INTERNAL_FACT_VALUE_KEY, entry.factValue());
        }
        envelope.set(
                INTERNAL_VALUE_JSON_KEY,
                entry.valueJson() == null ? JsonNodeFactory.instance.nullNode() : entry.valueJson()
        );
        return JsonNodeFactory.instance.objectNode().set(INTERNAL_ENVELOPE_KEY, envelope);
    }

    private JsonNode resolveEnvelope(JsonNode storedEntry) {
        if (storedEntry == null || !storedEntry.isObject() || storedEntry.size() != 1) {
            return null;
        }
        JsonNode envelope = storedEntry.get(INTERNAL_ENVELOPE_KEY);
        if (envelope == null
                || !envelope.isObject()
                || !INTERNAL_FORMAT_SENTINEL.equals(envelope.path(INTERNAL_FORMAT_KEY).asText())
                || envelope.path(INTERNAL_VERSION_KEY).asInt(-1) != INTERNAL_FORMAT_VERSION
                || !envelope.has(INTERNAL_FACT_VALUE_KEY)
                || !envelope.has(INTERNAL_VALUE_JSON_KEY)) {
            return null;
        }
        return envelope;
    }

    private void put(
            Map<CharacterSnapshotSlot, CharacterSnapshotEntry> entries,
            CharacterFactType factType,
            String factKey,
            String factValue,
            JsonNode valueJson
    ) {
        put(entries, factType, factKey, factValue, valueJson, true);
    }

    private void put(
            Map<CharacterSnapshotSlot, CharacterSnapshotEntry> entries,
            CharacterFactType factType,
            String factKey,
            String factValue,
            JsonNode valueJson,
            boolean factValuePersisted
    ) {
        CharacterSnapshotSlot slot = new CharacterSnapshotSlot(factType, factKey);
        entries.put(slot, new CharacterSnapshotEntry(slot, factValue, valueJson, factValuePersisted));
    }

    private String toFactValue(JsonNode valueJson) {
        if (valueJson == null || valueJson.isNull()) {
            return null;
        }
        JsonNode primary = valueJson.isObject() ? valueJson.get("value") : valueJson;
        if (primary != null && !primary.isNull()) {
            return primary.isValueNode() ? primary.asText() : primary.toString();
        }
        JsonNode name = valueJson.isObject() ? valueJson.get("name") : null;
        return name != null && name.isTextual() ? name.asText() : null;
    }

    private Integer resolveNonNegativeInteger(CharacterSnapshotEntry entry) {
        JsonNode valueNode = entry.valueJson();
        if (valueNode != null && valueNode.isObject()) {
            valueNode = valueNode.get("value");
        }
        if (valueNode != null && valueNode.isIntegralNumber() && valueNode.canConvertToInt()) {
            int value = valueNode.asInt();
            if (value >= 0) {
                return value;
            }
        }
        if (entry.factValue() != null) {
            try {
                int value = Integer.parseInt(entry.factValue().trim());
                if (value >= 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // 아래 공통 도메인 예외로 변환한다.
            }
        }
        throw new AppException(CharacterErrorCode.CHARACTER_SETTING_VALUE_INVALID);
    }

    private JsonNode nullIfEmpty(ObjectNode node) {
        return node.isEmpty() ? null : node;
    }
}
