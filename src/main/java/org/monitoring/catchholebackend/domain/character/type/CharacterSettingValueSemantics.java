package org.monitoring.catchholebackend.domain.character.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 설정값이 캐릭터의 기준값인지, 기준값에 적용되는 보정인지, 다른 값에서 계산된 결과인지 구분합니다.
 */
@RequiredArgsConstructor
@Getter
public enum CharacterSettingValueSemantics {

    /** 원문에서 직접 확인한 능력치처럼 계산의 기준이 되는 값입니다. */
    BASE_VALUE("기본값"),

    /** 기본값에 더하거나 빼는 장비·상태 효과 등의 보정값입니다. */
    MODIFIER("보정값"),

    /** 하나 이상의 다른 설정값을 이용해 계산한 결과값입니다. */
    DERIVED("파생값");

    private final String toKorean;
}
