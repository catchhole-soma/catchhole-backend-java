package org.monitoring.catchholebackend.domain.validation.type;
/** 검토 종료 보류 사유. RELEASE_HELD는 자동 종료가 추출 생성 조건에 막혔다는 안내이며 명시 검토 완료는 막지 않는다. */
public enum ValidationReviewHoldReason { EXECUTION_NOT_COMPLETED, PENDING_JUDGMENTS, NEEDS_FIX_PENDING, DIAGNOSTIC_ONLY, REPORT_INVALIDATED, BASELINE_NOT_READY, RELEASE_HELD }
