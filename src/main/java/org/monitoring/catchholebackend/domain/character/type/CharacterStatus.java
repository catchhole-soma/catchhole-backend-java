package org.monitoring.catchholebackend.domain.character.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum CharacterStatus {
    ACTIVE("활성"),
    ARCHIVED("보관됨");

    private final String toKorean;
}
