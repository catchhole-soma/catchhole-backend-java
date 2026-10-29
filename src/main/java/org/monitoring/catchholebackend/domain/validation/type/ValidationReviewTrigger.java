package org.monitoring.catchholebackend.domain.validation.type;
/** 검토 종료 receipt를 만든 경로. AUTOMATIC은 /complete의 서버 판정, EXPLICIT은 작가의 검토 완료 요청이다. */
public enum ValidationReviewTrigger { AUTOMATIC, EXPLICIT }
