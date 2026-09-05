package org.monitoring.catchholebackend.domain.character.type;

/** 1차 후보 key가 schema에 의해 해소된 방식이다. PATTERN STATUS만 2차에서 key를 바꿀 수 있다. */
public enum CharacterFactCanonicalKeyResolution {
    EXACT,
    ALIAS,
    PATTERN
}
