package org.monitoring.catchholebackend.domain.character.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;

@DisplayName("캐릭터 current Fact snapshot 조립 테스트")
class CharacterSnapshotAssemblerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CharacterSnapshotAssembler assembler = new CharacterSnapshotAssembler();

    @Test
    @DisplayName("모든 JSON fact type을 factKey별 object map으로 조립한다")
    void assembleGroupsCurrentFactsBySnapshotColumn() {
        JsonNode strength = objectMapper.createObjectNode().put("value", 12);
        JsonNode skill = objectMapper.createObjectNode().put("name", "은월참").put("level", 3);
        JsonNode item = objectMapper.createObjectNode().put("name", "회복포션").put("quantity", 2);
        JsonNode status = objectMapper.createObjectNode().put("active", true);
        JsonNode time = objectMapper.createObjectNode().put("episode", 7);

        CharacterSnapshot snapshot = assembler.assemble(List.of(
                fact(CharacterFactType.STAT, "stats.strength", strength),
                fact(CharacterFactType.SKILL, "skill.은월참", skill),
                fact(CharacterFactType.ITEM, "item.회복포션", item),
                fact(CharacterFactType.STATUS, "status.부상", status),
                fact(CharacterFactType.TIME, "time.첫전투", time),
                fact(CharacterFactType.LEVEL, "level", objectMapper.createObjectNode().put("value", 10))
        ));

        assertThat(snapshot.statsJson().get("stats.strength")).isEqualTo(strength);
        assertThat(snapshot.skillsJson().get("skill.은월참")).isEqualTo(skill);
        assertThat(snapshot.itemsJson().get("item.회복포션")).isEqualTo(item);
        assertThat(snapshot.statusesJson().get("status.부상")).isEqualTo(status);
        assertThat(snapshot.statusesJson().get("time.첫전투")).isEqualTo(time);
        assertThat(snapshot.statusesJson().has("level")).isFalse();
    }

    @Test
    @DisplayName("current JSON fact가 없는 snapshot 그룹은 null로 둔다")
    void assembleReturnsNullForEmptySnapshotGroups() {
        CharacterSnapshot snapshot = assembler.assemble(List.of(
                fact(CharacterFactType.AGE, "age", objectMapper.createObjectNode().put("value", 17))
        ));

        assertThat(snapshot.statsJson()).isNull();
        assertThat(snapshot.skillsJson()).isNull();
        assertThat(snapshot.itemsJson()).isNull();
        assertThat(snapshot.statusesJson()).isNull();
    }

    private CharacterFact fact(CharacterFactType factType, String factKey, JsonNode valueJson) {
        return CharacterFact.create(
                null,
                null,
                factType,
                factKey,
                null,
                null,
                valueJson,
                null,
                null,
                null,
                null,
                null
        );
    }
}
