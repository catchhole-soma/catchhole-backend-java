package org.monitoring.catchholebackend.domain.analysis.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AnalysisBatchStatus {

    IN_PROGRESS("분석 진행 중"),
    CANCELED("작품 삭제로 분석 취소"),
    PARTIALLY_FAILED("일부 분석 실패"),
    FAILED("분석 실패"),
    REVIEW_REQUIRED("설정 후보 검토 필요"),
    COMPLETED("분석 완료");

    private final String toKorean;
}
