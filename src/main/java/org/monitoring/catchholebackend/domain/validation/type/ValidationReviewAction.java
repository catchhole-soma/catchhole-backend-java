package org.monitoring.catchholebackend.domain.validation.type;
/** 화면 안내용 행동 힌트. 실제 요청은 서버가 다시 검증한다. COMPLETE_REVIEW는 POST /{reportId}/review-completion이다. */
public enum ValidationReviewAction { SAVE_JUDGMENT, COMPLETE_REVIEW, RETRY, RECHECK }
