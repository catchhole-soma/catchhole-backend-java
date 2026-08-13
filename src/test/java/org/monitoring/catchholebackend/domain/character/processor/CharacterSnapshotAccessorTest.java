package org.monitoring.catchholebackend.domain.character.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;

class CharacterSnapshotAccessorTest {

    private final CharacterSnapshotAccessor accessor = new CharacterSnapshotAccessor();

    @Test
    void envelopeRoundTripPreservesFactValueSeparatelyFromRichValueJson() {
        WorkCharacter character = character(null);
        CharacterSnapshotSlot slot = new CharacterSnapshotSlot(CharacterFactType.SKILL, "skill.빙결검");
        JsonNode richValueJson = JsonNodeFactory.instance.objectNode()
                .put("name", "빙결검")
                .put("level", 5);
        Map<CharacterSnapshotSlot, CharacterSnapshotEntry> entries = Map.of(
                slot,
                accessor.entry(CharacterFactType.SKILL, "skill.빙결검", "Lv.5", richValueJson)
        );

        accessor.replace(character, entries);

        CharacterSnapshotEntry restored = accessor.read(character).get(slot);
        assertThat(restored.factValue()).isEqualTo("Lv.5");
        assertThat(restored.valueJson()).isEqualTo(richValueJson);
        assertThat(restored.factValuePersisted()).isTrue();
    }

    @Test
    void provenanceOnlyChangeIncrementsSnapshotVersionExactlyOnce() {
        WorkCharacter character = character(null);
        CharacterSnapshotSlot slot = new CharacterSnapshotSlot(CharacterFactType.SKILL, "skill.빙결검");
        Map<CharacterSnapshotSlot, CharacterSnapshotEntry> entries = Map.of(
                slot,
                accessor.entry(
                        CharacterFactType.SKILL,
                        "skill.빙결검",
                        "Lv.5",
                        JsonNodeFactory.instance.objectNode().put("level", 5)
                )
        );
        accessor.replace(character, entries);
        long valueVersion = character.getSnapshotVersion();

        accessor.replace(character, entries, true);

        assertThat(character.getSnapshotVersion()).isEqualTo(valueVersion + 1);
    }

    @Test
    void legacyRawSnapshotUsesLastProvenanceFactValueWithoutReplacingRawValueJson() {
        JsonNode legacyValueJson = JsonNodeFactory.instance.objectNode()
                .put("name", "빙결검")
                .put("level", 5);
        WorkCharacter character = character(
                JsonNodeFactory.instance.objectNode().set("skill.빙결검", legacyValueJson)
        );
        CharacterFact earlier = CharacterFact.createManual(
                character,
                CharacterFactType.SKILL,
                "skill.빙결검",
                "Lv.4",
                legacyValueJson
        );
        CharacterFact latest = CharacterFact.createManual(
                character,
                CharacterFactType.SKILL,
                "skill.빙결검",
                "Lv.5",
                legacyValueJson
        );
        CharacterSnapshotSlot slot = new CharacterSnapshotSlot(CharacterFactType.SKILL, "skill.빙결검");

        long snapshotVersion = character.getSnapshotVersion();
        Map<CharacterSnapshotSlot, CharacterSnapshotEntry> restoredEntries = accessor.read(
                character,
                Map.of(slot, List.of(earlier, latest))
        );
        CharacterSnapshotEntry restored = restoredEntries.get(slot);

        assertThat(restored.factValue()).isEqualTo("Lv.5");
        assertThat(restored.valueJson()).isEqualTo(legacyValueJson);
        assertThat(restored.factValuePersisted()).isFalse();

        accessor.replace(character, restoredEntries);

        assertThat(character.getSkillsJson().get("skill.빙결검")).isEqualTo(legacyValueJson);
        assertThat(character.getSnapshotVersion()).isEqualTo(snapshotVersion);
    }

    @Test
    void reservedKeyWithoutFormatSentinelRemainsLegacyRawValue() {
        JsonNode reservedKeyValue = JsonNodeFactory.instance.objectNode().set(
                "__catchhole_snapshot",
                JsonNodeFactory.instance.objectNode()
                        .put("version", 1)
                        .put("factValue", "사용자 값")
                        .set("valueJson", JsonNodeFactory.instance.objectNode().put("value", "원문"))
        );
        WorkCharacter character = character(
                JsonNodeFactory.instance.objectNode().set("skill.사용자_설정", reservedKeyValue)
        );
        CharacterSnapshotSlot slot = new CharacterSnapshotSlot(CharacterFactType.SKILL, "skill.사용자_설정");

        CharacterSnapshotEntry restored = accessor.read(character).get(slot);

        assertThat(restored.valueJson()).isEqualTo(reservedKeyValue);
        assertThat(restored.factValuePersisted()).isFalse();
    }

    @Test
    void scalarSnapshotFallsBackToFactValueWhenValueJsonIsMissing() {
        WorkCharacter character = character(null);
        CharacterSnapshotSlot slot = new CharacterSnapshotSlot(CharacterFactType.AGE, "age");

        accessor.replace(character, Map.of(
                slot,
                accessor.entry(CharacterFactType.AGE, "age", "23", null)
        ));

        assertThat(character.getCurrentAge()).isEqualTo(23);
        assertThat(accessor.read(character).get(slot).factValue()).isEqualTo("23");
    }

    private WorkCharacter character(JsonNode skillsJson) {
        return WorkCharacter.create(
                null,
                "수아",
                null,
                null,
                null,
                null,
                null,
                skillsJson,
                null,
                null,
                null
        );
    }
}
