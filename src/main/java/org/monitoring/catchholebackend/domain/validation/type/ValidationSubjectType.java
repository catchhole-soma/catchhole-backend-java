package org.monitoring.catchholebackend.domain.validation.type;

/** 설정 충돌 finding이 가리키는 고정 기준 항목의 대상 종류(#314 B2). 오탈자 finding에는 없다. */
public enum ValidationSubjectType {
    /** 고정 기준의 캐릭터 항목(domain=characters). */
    CHARACTER,
    /** 고정 기준의 세계관 대상 또는 그 확정 이력(domain=worldSettings, worldHistory). */
    WORLD_SETTING
}
