package org.monitoring.catchholebackend.domain.analysis.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum AnalysisJobStatus {
    PENDING("분석 대기"),
    RUNNING("분석 진행 중"),
    SUCCEEDED("분석 성공"),
    FAILED("분석 실패");

    private final String toKorean;
}
