package org.monitoring.catchholebackend.domain.work.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum WorkPurgeStatus {

    REQUESTED("삭제 요청 접수"),
    PROCESSING("영구 삭제 처리 중"),
    COMPLETED("영구 삭제 완료"),
    PARTIAL_FAILED("일부 삭제 실패"),
    FAILED("삭제 실패");

    private final String toKorean;

    public boolean canRetry() {
        return this == PARTIAL_FAILED || this == FAILED;
    }
}
