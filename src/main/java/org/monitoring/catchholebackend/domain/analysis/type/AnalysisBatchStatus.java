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
    COMPLETED("분석 완료"),
    ERROR_REVIEW_PREPARING("오류 검사 기준 준비 중"),
    ERROR_REVIEW_PENDING("오류 검사·검토 대기"),
    ERROR_REVIEW_HELD("오류 검수 보류"),
    ERROR_REVIEW_INVALIDATED("오류 검수 무효화");

    private final String toKorean;
}
