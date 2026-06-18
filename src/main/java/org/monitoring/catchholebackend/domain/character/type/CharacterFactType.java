package org.monitoring.catchholebackend.domain.character.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum CharacterFactType {
    AGE("나이"),
    LEVEL("레벨"),
    STAT("스탯"),
    SKILL("스킬"),
    ITEM("아이템"),
    STATUS("상태"),
    TIME("시간");

    private final String toKorean;
}
