package org.monitoring.catchholebackend.domain.validation.type;

/**
 * 목록·요약·이력이 함께 쓰는 파생 검수 단계(GH312 S4). 저장하지 않으며 {@code ValidationReviewStages.resolve}만 계산한다.
 * 기존 report status·errorReviewStatus·extractionStatus·candidateStatus를 대체하지 않고 한 값으로 요약한다.
 */
public enum ValidationReviewStage {
    /** 고정 기준 준비 중. */
    PREPARING_BASELINE,
    /** 기준 READY, 전용 검사 실행 전·대기·진행(Job 없음/QUEUED/RUNNING). */
    VALIDATING,
    /** 검사 종료(COMPLETED/PARTIAL/FAILED) 뒤 검토 종료 receipt 없음. 판단 남음·수정 필요·명시 완료 대기·자동 종료 보류·진단 전용을 모두 포함한다. */
    AWAITING_REVIEW,
    /** 검토 종료 receipt가 있고 설정 추출이 진행 중이거나 아직 회차 전체 확정 평가 전이다. */
    REVIEW_COMPLETED,
    /** 검토 종료 뒤 release 추출 Job이 실패·취소됐거나 재개가 필요한 기록(INCOMPLETE/INVALIDATED)이다. */
    EXTRACTION_FAILED,
    /** 추출은 끝났고 회차 전체 확정이 보류(HELD_REVIEW)됐다. 확정 설정 쓰기 0. */
    APPLICATION_HELD,
    /** 회차 전체 확정 APPLIED. */
    APPLIED,
    /** 기준 준비 보류(HELD_INCOMPLETE). */
    HELD_INCOMPLETE,
    /** 무효화·퇴역한 report. 재검수가 필요하다. */
    INVALIDATED
}
