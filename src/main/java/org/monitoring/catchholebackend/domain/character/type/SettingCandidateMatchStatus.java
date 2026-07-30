package org.monitoring.catchholebackend.domain.character.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 설정 후보의 현재 캐릭터 연결 상태.
 * Worker가 생성한 초기 판단과 Spring confirm 후 같은 이름 후보에 적용한 자동 연결을 구분한다.
 */
@Getter
@RequiredArgsConstructor
public enum SettingCandidateMatchStatus {

    MATCHED("기존 캐릭터와 연결됨"),
    AUTO_MATCHED_BY_NAME("같은 이름으로 자동 연결됨"),
    UNRESOLVED("연결할 기존 캐릭터 없음"),
    AMBIGUOUS("연결 후보가 애매함");

    private final String toKorean;
}
