package org.monitoring.catchholebackend.domain.character.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Registry schema가 공통 정책으로 관리되는 seed인지 POC 검증을 위해 추가된 seed인지 나타냅니다.
 * source는 이력과 관리 목적의 구분이며 활성 조회 포함 여부는 enabled로 판단합니다.
 */
@RequiredArgsConstructor
@Getter
public enum CharacterSettingSchemaSource {

    /** 장르와 작품에 공통으로 적용하는 시스템 기본 schema입니다. */
    SYSTEM_SEED("시스템 기본 시드"),

    /** POC와 개발 검증을 위해 추가한 schema입니다. */
    DEV_SEED("개발 검증 시드");

    private final String toKorean;
}
