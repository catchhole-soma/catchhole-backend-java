package org.monitoring.catchholebackend.domain.character.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SettingCandidateMatchStatus {

    MATCHED("기존 캐릭터와 연결됨"),
    UNRESOLVED("연결할 기존 캐릭터 없음"),
    AMBIGUOUS("연결 후보가 애매함");

    private final String toKorean;
}
