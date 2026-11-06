package org.monitoring.catchholebackend.domain.validation.type;

/** #381 확정 설정 잠금의 단계. error.context.lockReason으로 Front에 전달한다. */
public enum ValidationSettingsLockReason {
    /** 검수 실행이 대기(QUEUED, 대기 잠금 시간 안) 또는 실행(RUNNING, lease 유효) 중이다. 그 리포트의 정정도 막는다. */
    EXECUTION,
    /** 확정 전 검수(BEFORE_CONFIRMATION)의 실행은 끝났고(COMPLETED) 검토 종료 receipt가 아직 없다. 그 리포트의 정정만 허용한다. */
    REVIEW
}
