package org.monitoring.catchholebackend.domain.character.type;

import java.util.List;

public enum CharacterFactSearchType {
    ALL,
    AGE,
    LEVEL,
    STAT,
    SKILL,
    ITEM,
    STATUS;

    private static final List<CharacterFactType> SEARCHABLE_TYPES = List.of(
            CharacterFactType.AGE,
            CharacterFactType.LEVEL,
            CharacterFactType.STAT,
            CharacterFactType.SKILL,
            CharacterFactType.ITEM,
            CharacterFactType.STATUS
    );

    public List<CharacterFactType> toFactTypes() {
        if (this == ALL) {
            return SEARCHABLE_TYPES;
        }
        return List.of(CharacterFactType.valueOf(name()));
    }
}
