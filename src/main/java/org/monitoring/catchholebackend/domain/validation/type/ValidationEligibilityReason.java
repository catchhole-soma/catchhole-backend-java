package org.monitoring.catchholebackend.domain.validation.type;

/**
 * 신규 회차 검수 접수 자격 판정(#314 B1). {@code processor.ValidationIntakeEligibility} 한 곳이 계산하며
 * 접수({@code POST /episode-validations})와 조회 전용 자격 조회가 같은 값을 읽는다. 위에서부터 처음 맞는 사유를 쓴다.
 */
public enum ValidationEligibilityReason {
    /** 검수를 시작할 수 있다. */
    ELIGIBLE,
    /** 회차에 현재 검수 report가 이미 있다. 새로 접수하지 않고 그 report를 사용한다(같은 입력의 최초 접수 재전송만 같은 식별자를 반환). */
    REPORT_EXISTS,
    /** 작품에 없는 회차이거나 보관된 회차다. */
    EPISODE_NOT_FOUND,
    /** 마지막 회차 뒤에 단일 회차로 업로드한 원고가 아니다(다회차·교체·직접 수정 원고 포함). */
    NOT_SINGLE_APPEND_UPLOAD,
    /** 작품의 최신 활성 회차가 아니다. */
    NOT_LATEST_EPISODE,
    /** 회차 원문 파기 요청이 남아 있다. */
    SOURCE_PURGE_PENDING,
    /** 회차에 이미 일반 분석 Job이 있다(target-only legacy 관계 포함). */
    ANALYSIS_ALREADY_STARTED,
    /** 작품의 다른 회차 검수가 검토·설정 저장을 마치지 않았다. */
    NEXT_EPISODE_BLOCKED,
    /** 첫 회차 검수인데 비교할 확정 설정·설정집이 없다. */
    COMPARISON_MATERIAL_REQUIRED
}
