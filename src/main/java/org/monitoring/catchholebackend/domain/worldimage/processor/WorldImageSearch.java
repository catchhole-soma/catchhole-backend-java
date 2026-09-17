package org.monitoring.catchholebackend.domain.worldimage.processor;

import java.text.Normalizer;
import java.util.Locale;

public final class WorldImageSearch {
    private WorldImageSearch() {}

    public static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Z}·ㆍ._/\\\\-]+", "");
    }

    public static String containsQuery(String value) {
        return "%" + normalize(value).replace("!", "!!").replace("%", "!%") + "%";
    }
}
