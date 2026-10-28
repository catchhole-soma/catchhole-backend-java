package org.monitoring.catchholebackend.domain.validation.type;

/** 실제 검사와 작가 검토 완료 전에는 추출을 시작할 수 없다. 이번 foundation에는 release 상태가 없다. */
public enum ValidationReportStatus { PREPARING_BASELINE, WAITING_FOR_ERROR_REVIEW, HELD_INCOMPLETE, INVALIDATED }
