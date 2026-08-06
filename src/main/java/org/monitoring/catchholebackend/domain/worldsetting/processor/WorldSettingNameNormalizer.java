package org.monitoring.catchholebackend.domain.worldsetting.processor;

import java.text.Normalizer;
import java.util.Locale;

public final class WorldSettingNameNormalizer {

    private WorldSettingNameNormalizer() {
    }

    public static String displayName(String value) {
        return value == null ? null : Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
    }

    public static String duplicateKey(String value) {
        String displayName = displayName(value);
        return displayName == null ? null : displayName.toLowerCase(Locale.ROOT);
    }
}
