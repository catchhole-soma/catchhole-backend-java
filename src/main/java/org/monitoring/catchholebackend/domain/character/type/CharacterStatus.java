package org.monitoring.catchholebackend.domain.character.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum CharacterStatus {

    /**
     * 작품 안에서 현재 사용할 수 있는 캐릭터 대표 설정 상태.
     * WorkCharacter.create()가 기본값으로 설정한다.
     */
    ACTIVE("활성"),

    /**
     * 캐릭터를 일반 조회/분석 기준에서 제외할 때 사용하는 보관 상태.
     * WorkCharacter.archive()로 전환하며, 복구 API는 아직 정의하지 않았다.
     */
    ARCHIVED("보관됨");

    private final String toKorean;
}
