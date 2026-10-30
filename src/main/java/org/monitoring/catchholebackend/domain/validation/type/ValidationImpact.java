package org.monitoring.catchholebackend.domain.validation.type;

/**
 * 설정 충돌 finding의 영향 등급(#314 B2). 검수 Worker 판정 payload의 {@code impact}와 같은 값이며
 * {@code ValidationDecisionValidator}가 NO_CONFLICT 이외 판정에 이 세 값만 허용한다. 오탈자 finding에는 없다.
 */
public enum ValidationImpact {
    /** 행동·사건 성립에 직접 영향. */
    SEVERE,
    /** 배경·관계 이해가 바뀜. */
    CAUTION,
    /** 부수 묘사. */
    REFERENCE
}
