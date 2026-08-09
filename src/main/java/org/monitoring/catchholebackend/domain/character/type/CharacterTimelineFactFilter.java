package org.monitoring.catchholebackend.domain.character.type;

import java.util.List;

/**
 * 타임라인 조회에만 사용하는 Fact 유형 필터다.
 * DB에 저장되는 {@link CharacterFactType}에 조회 전용 값 ALL을 섞지 않는다.
 */
public enum CharacterTimelineFactFilter {
    ALL,
    PROFILE,
    AGE,
    LEVEL,
    STAT,
    SKILL,
    ITEM,
    STATUS;

    private static final List<CharacterFactType> TIMELINE_TYPES = List.of(
            CharacterFactType.PROFILE,
            CharacterFactType.AGE,
            CharacterFactType.LEVEL,
            CharacterFactType.STAT,
            CharacterFactType.SKILL,
            CharacterFactType.ITEM,
            CharacterFactType.STATUS
    );

    public List<CharacterFactType> toFactTypes() {
        if (this == ALL) {
            return TIMELINE_TYPES;
        }
        return List.of(CharacterFactType.valueOf(name()));
    }

    public static List<CharacterFactType> supportedFactTypes() {
        return TIMELINE_TYPES;
    }
}
