package org.monitoring.catchholebackend.domain.validation.type;
/** 회차 전체 확정을 보류한 이유. 하나라도 있으면 그 시도는 어떤 설정도 반영하지 않는다(GH312 S3). */
public enum ValidationApplicationHoldCode {
    /** release 추출 Job이 실패·취소됐거나 불변 기록이 INCOMPLETE/INVALIDATED라 재개가 필요하다. */
    EXTRACTION_NOT_FINISHED,
    /** 현재 report가 무효화됐다(원고·기준·확정 설정 변경). 재검수가 필요하다. */
    REPORT_INVALIDATED,
    /** report batch가 회차의 현재 원본 업로드 batch가 아니거나 추출 Job의 원문 버전이 현재 회차와 다르다. */
    SOURCE_CHANGED,
    /** 비교가 아직 끝나지 않았거나 캐릭터 연결을 기다린다. */
    COMPARISON_PENDING,
    /** 비교 실패 또는 재비교 필요. */
    COMPARISON_FAILED,
    /** 비교가 AI 사용량 소진으로 중단됐다. */
    TOKEN_QUOTA,
    /** AI 비교가 검토 필요·병합 충돌을 제안했거나 자동 반영 보류 사유가 있다. detail에 원래 사유를 둔다. */
    REVIEW_REQUIRED,
    /** 후보 값이 현재 schema 계약에 맞지 않는다(valueValidation INVALID). */
    VALUE_INVALID,
    /** 대상 인물·세계관 주체를 특정할 수 없다(미상·모호·연결 대기·주체 해소 실패). */
    SUBJECT_UNRESOLVED,
    /** 세계관 범위를 정할 수 없거나 기존 범위와 다르다. */
    SCOPE_UNRESOLVED,
    /** 작가가 값·반영 방식·최종 결정을 직접 저장한 후보. S3의 회차 전체 확정은 자동 반영 규칙으로 판정 가능한 후보만 반영한다. */
    AUTHOR_EDIT_PENDING,
    /** 계획 뒤 실제 반영 단계에서 기존 자동 반영 규칙이 후보를 보류했다(현재 설정 변경·의존·보호 등). detail에 원래 사유를 둔다. */
    CANDIDATE_CHANGED,
    /** 반영 트랜잭션이 예외로 전부 되돌려졌다. detail과 lastErrorCode에 오류 코드를 둔다. */
    UNKNOWN_ERROR
}
