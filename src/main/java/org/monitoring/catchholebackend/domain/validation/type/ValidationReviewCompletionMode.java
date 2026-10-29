package org.monitoring.catchholebackend.domain.validation.type;
/** 검토 종료 경로. AUTOMATIC_ELIGIBLE/EXPLICIT_REQUIRED도 S1에서는 추출을 시작하지 않는다. */
public enum ValidationReviewCompletionMode { AUTOMATIC_ELIGIBLE, EXPLICIT_REQUIRED, BLOCKED }
