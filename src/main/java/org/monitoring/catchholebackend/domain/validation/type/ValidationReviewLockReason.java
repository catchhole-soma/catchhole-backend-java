package org.monitoring.catchholebackend.domain.validation.type;
/** 판단 저장을 막는 이유. 검사 진행·재시도 대기 중에는 발견 항목을 읽기 전용으로만 보여주고 검토 종료 뒤에는 판단을 바꾸지 않는다. */
public enum ValidationReviewLockReason { REPORT_INVALIDATED, BASELINE_NOT_READY, EXECUTION_NOT_STARTED, EXECUTION_IN_PROGRESS, EXECUTION_CANCELED, REVIEW_COMPLETED }
