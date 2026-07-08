package org.monitoring.catchholebackend.domain.character.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SettingCandidateCharacterMatchResolutionType {

    MATCH_EXISTING("기존 캐릭터에 연결"),
    CREATE_NEW("새 캐릭터로 확정");

    private final String toKorean;
}
