package org.monitoring.catchholebackend.domain.worldsetting.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum WorldSettingRecomparisonReason {
    TARGET_CREATED("동일한 세계관 대상이 먼저 생성되었습니다."),
    TARGET_MISSING("비교했던 세계관 대상을 찾을 수 없습니다."),
    TARGET_IDENTITY_CHANGED("비교했던 세계관 대상의 분류 또는 대상명이 변경되었습니다."),
    PROPERTY_ADDED("추가하려던 설정이 먼저 생성되었습니다."),
    PROPERTY_REMOVED("비교했던 기존 설정이 삭제되었습니다."),
    PROPERTY_CHANGED("비교했던 기존 설정값이 변경되었습니다."),
    PROPERTY_PATH_CONFLICT("설정 경로가 루트 설정과 하위 범위 역할로 충돌합니다.");

    private final String message;
}
