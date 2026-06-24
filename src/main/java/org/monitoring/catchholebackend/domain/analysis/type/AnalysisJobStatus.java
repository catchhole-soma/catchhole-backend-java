package org.monitoring.catchholebackend.domain.analysis.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum AnalysisJobStatus {

    /**
     * 사용자가 분석 작업을 생성한 뒤 Worker가 아직 가져가지 않은 상태.
     * AnalysisJobService가 AnalysisJob.create()로 생성하며, Worker claim 후보가 된다.
     */
    PENDING("분석 대기"),

    /**
     * Python AI Worker가 내부 claim API로 작업을 선점해 처리 중인 상태.
     * AnalysisJobWorkerService가 start()를 호출하면서 modelName, currentStep, startedAt을 함께 기록한다.
     */
    RUNNING("분석 진행 중"),

    /**
     * Worker가 분석 결과 저장을 끝내고 완료 API를 호출한 상태.
     * succeed()에서 summaryJson, token count, completedAt을 기록한다.
     */
    SUCCEEDED("분석 성공"),

    /**
     * Worker 처리 실패 또는 분석 대상 회차 없음으로 종료된 상태.
     * TODO: FAILED 작업을 재시도할 때 기존 작업을 되살릴지 새 AnalysisJob을 만들지 후속 정책으로 결정한다.
     */
    FAILED("분석 실패");

    private final String toKorean;
}
