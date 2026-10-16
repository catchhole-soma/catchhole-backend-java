package org.monitoring.catchholebackend.domain.settingtool.processor;

import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.Locale;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry;

/** 조건 검색은 현재 snapshot의 scalar 대표값만 비교하고 서술문·과거 provenance를 추론하지 않는다. */
public final class SettingToolScalarValue {
    private SettingToolScalarValue() {}

    public static boolean matches(CharacterSnapshotEntry entry, String expected) {
        if (entry == null) return false;
        JsonNode value = entry.valueJson();
        if (value != null && value.isObject()) {
            JsonNode primary = value.get("value");
            value = primary != null && !primary.isNull() ? primary : value.get("name");
        }
        if (value == null || value.isNull() || !value.isValueNode()) return false;
        return normalize(value.asText()).equals(normalize(expected));
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }
}
