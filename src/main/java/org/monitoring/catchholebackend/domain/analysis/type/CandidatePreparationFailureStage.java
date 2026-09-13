package org.monitoring.catchholebackend.domain.analysis.type;

/** 비교 입력을 만들기 전 후보별로 격리한 실패다. 실행권·저장·입력 무효화 오류에는 사용하지 않는다. */
public enum CandidatePreparationFailureStage {
    SUBJECT_RESOLUTION,
    COMPARISON_PREPARATION
}
