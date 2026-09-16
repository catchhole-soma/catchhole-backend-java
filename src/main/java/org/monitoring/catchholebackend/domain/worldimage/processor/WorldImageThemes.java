package org.monitoring.catchholebackend.domain.worldimage.processor;

import org.monitoring.catchholebackend.domain.work.type.WorkGenre;

public final class WorldImageThemes {
    private WorldImageThemes() {}

    public static String forGenre(WorkGenre genre) {
        if (genre == null) return "modern-common";
        return switch (genre) {
            case FANTASY -> "fantasy";
            case ROMANCE, COMEDY, SLICE_OF_LIFE, ETC -> "modern-common";
            case MARTIAL_ARTS -> "wuxia";
            case SF -> "sf";
            case MYSTERY -> "mystery";
            case HORROR -> "horror";
            case SPORTS -> "sports";
        };
    }
}

