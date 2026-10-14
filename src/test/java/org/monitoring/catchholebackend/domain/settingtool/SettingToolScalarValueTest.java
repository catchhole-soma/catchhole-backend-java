package org.monitoring.catchholebackend.domain.settingtool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.settingtool.processor.SettingToolScalarValue;

@DisplayName("현재 설정 조건 검색의 scalar 경계")
class SettingToolScalarValueTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            "ÉLF" | élf | true
            {"value":"north guild"} | NORTH GUILD | true
            {"value":null,"name":"north guild"} | north guild | true
            {"description":"north guild"} | north guild | false
            {"value":["north guild"]} | north guild | false
            ["north guild"] | north guild | false
            null | north guild | false
            "north guild captain" | north guild | false
            "south guild" | north guild | false
            "north  guild" | north guild | false
            "  north guild  " | north guild | true
            "3.0" | 3 | false
            """ )
    @DisplayName("문자 정규화는 허용하지만 문장·배열·과거 표시값·숫자 동등성으로 조건을 확장하지 않는다")
    void matchesOnlyCanonicalScalar(String currentJson, String expected, boolean matches) throws Exception {
        var entry = new CharacterSnapshotEntry(new CharacterSnapshotSlot(CharacterFactType.PROFILE, "profile.affiliation"),
                expected, json.readTree(currentJson), true);
        assertThat(SettingToolScalarValue.matches(entry, expected)).isEqualTo(matches);
    }
}
