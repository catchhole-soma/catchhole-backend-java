package org.monitoring.catchholebackend.domain.analysis.type;

/**
 * 분석 배치 목록에서 고를 배치 종류. 화면은 분석 목록(설정 추출)과 오류 리포트 목록(신규 회차 검수)을 따로 보여 주므로,
 * 화면에서 걸러 내면 페이지마다 카드 수가 달라져 서버 페이지 단위로 나눈다.
 * 검토를 마쳐 설정 추출이 시작된 검수 batch는 ANALYSIS와 VALIDATION 양쪽에 나온다.
 */
public enum AnalysisBatchKind {
    /** 모든 배치(기존 동작). */
    ALL,
    /** 공개 분석 Job(SETTING_EXTRACTION·EPISODE_VALIDATION)이 있는 배치. 검수 batch는 검토 종료가 만든 추출 Job부터 포함한다. */
    ANALYSIS,
    /** 현재 검수 report가 있는 신규 회차 검수 배치. */
    VALIDATION
}
