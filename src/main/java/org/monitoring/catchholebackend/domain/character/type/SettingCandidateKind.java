package org.monitoring.catchholebackend.domain.character.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * AI가 추출한 후보가 설정값인지, 이름만 확인된 캐릭터 발견인지 구분한다.
 */
@Getter
@RequiredArgsConstructor
public enum SettingCandidateKind {

    SETTING("설정"),
    CHARACTER_DISCOVERY("캐릭터 발견");

    private final String toKorean;
}
