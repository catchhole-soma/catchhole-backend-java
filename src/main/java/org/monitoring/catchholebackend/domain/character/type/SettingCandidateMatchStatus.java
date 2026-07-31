package org.monitoring.catchholebackend.domain.character.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 설정 후보의 현재 캐릭터 연결 상태.
 * 분석 시점부터 존재한 캐릭터와 이번 confirm에서 새로 생성한 캐릭터의 연결을 구분한다.
 */
@Getter
@RequiredArgsConstructor
public enum SettingCandidateMatchStatus {

    MATCHED("기존 캐릭터와 연결됨"),
    AUTO_MATCHED_BY_NAME("신규 캐릭터에 연결됨"),
    UNRESOLVED("연결할 기존 캐릭터 없음"),
    AMBIGUOUS("연결 후보가 애매함");

    private final String toKorean;
}
