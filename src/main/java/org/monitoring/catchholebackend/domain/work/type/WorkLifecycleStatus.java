package org.monitoring.catchholebackend.domain.work.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum WorkLifecycleStatus {

    ACTIVE("이용 가능"),
    PURGING("영구 삭제 중");

    private final String toKorean;
}
