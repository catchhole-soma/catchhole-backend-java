package org.monitoring.catchholebackend.domain.worldsetting.type;

/** AI 비교 제안이다. REVIEW_REQUIRED는 사용자가 concrete operation을 선택하기 전 상태다. */
public enum WorldSettingSuggestedOperation {
    ADD,
    UPDATE,
    MERGE,
    EXCLUDE,
    REVIEW_REQUIRED;

    public static WorldSettingSuggestedOperation fromFinalOperation(WorldSettingOperation operation) {
        return valueOf(operation.name());
    }

    public boolean matchesFinalOperation(WorldSettingOperation operation) {
        return operation != null && name().equals(operation.name());
    }
}
