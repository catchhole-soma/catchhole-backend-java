package org.monitoring.catchholebackend.domain.validation.type;
/** /review의 release 추출 Job 상태. 검토 종료 전 NOT_STARTED, PENDING/RUNNING은 RUNNING, SEALED 성공 SUCCEEDED, 실패·취소·재개 필요 기록 FAILED. */
public enum ValidationExtractionState { NOT_STARTED, RUNNING, SUCCEEDED, FAILED }
