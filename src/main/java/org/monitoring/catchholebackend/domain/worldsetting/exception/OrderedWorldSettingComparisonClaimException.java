package org.monitoring.catchholebackend.domain.worldsetting.exception;

import org.monitoring.catchholebackend.global.exception.AppException;

/** 선검증 실패를 저장한 채 Worker에게 후속 단계 중단을 알리는 claim 전용 예외다. */
public class OrderedWorldSettingComparisonClaimException extends AppException {
    public OrderedWorldSettingComparisonClaimException() {
        super(WorldSettingErrorCode.WORLD_SETTING_ORDERED_COMPARISON_FAILED);
    }
}
