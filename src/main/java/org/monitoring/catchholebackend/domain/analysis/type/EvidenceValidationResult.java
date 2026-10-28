package org.monitoring.catchholebackend.domain.analysis.type;

/** 고정 원문 인용의 구조·위치 오류와 위치 없는 인용 불일치를 구분한다. */
public enum EvidenceValidationResult {
    /** 모든 인용과 명시한 위치가 원문에 일치한다. */
    VALID,
    /** 위치를 명시하지 않은 인용이 원문에 없다. */
    QUOTE_NOT_FOUND,
    /** 근거 배열 또는 인용 문자열의 구조가 올바르지 않다. */
    MALFORMED_SPANS,
    /** 명시한 위치의 형식·범위 또는 해당 원문 slice가 올바르지 않다. */
    INVALID_OFFSETS
}
