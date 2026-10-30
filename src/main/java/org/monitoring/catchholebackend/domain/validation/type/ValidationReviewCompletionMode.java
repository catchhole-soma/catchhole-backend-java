package org.monitoring.catchholebackend.domain.validation.type;
/**
 * 검토 종료 경로. AUTOMATIC_ELIGIBLE은 /complete가 같은 트랜잭션에서 자동 종료하며, 그 자리에서 추출 생성 조건이 막혀
 * 종료하지 못한 경우에만 외부에 남아 명시 검토 완료로 다시 시도한다. COMPLETED는 현재 report의 검토 종료 receipt가 있다.
 */
public enum ValidationReviewCompletionMode { AUTOMATIC_ELIGIBLE, EXPLICIT_REQUIRED, BLOCKED, COMPLETED }
