package org.monitoring.catchholebackend.domain.analysis.service;

import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobCompleteRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobFailRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobProgressRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobPayload;

public interface AnalysisJobWorkerService {

    /**
     * 대기 중인 분석 작업 하나를 선점해 RUNNING 상태로 전환하고 Worker 처리 정보를 기록한다.
     * 작업의 업로드 batch에서 회차 파일을 찾고, 해당 파일에서 생성된 회차들을 payload로 반환한다.
     * 대기 작업이 없거나 처리할 회차가 없으면 빈 Optional을 반환하며, 회차가 없는 작업은 실패 처리한다.
     */
    Optional<WorkerAnalysisJobPayload> claimAnalysisJob(WorkerAnalysisJobClaimRequest request);

    /**
     * 실행 중인 분석 작업의 현재 처리 단계를 갱신한다.
     */
    void updateProgress(UUID analysisJobId, WorkerAnalysisJobProgressRequest request);

    /**
     * 실행 중인 분석 작업을 성공 상태로 전환한다.
     * Worker가 전달한 요약 JSON과 입력/출력 토큰 사용량을 결과 메타데이터로 저장한다.
     */
    void completeAnalysisJob(UUID analysisJobId, WorkerAnalysisJobCompleteRequest request);

    /**
     * 실행 중인 분석 작업을 실패 상태로 전환한다.
     * Worker가 전달한 실패 사유를 저장해 이후 조회 응답에서 원인을 확인할 수 있게 한다.
     */
    void failAnalysisJob(UUID analysisJobId, WorkerAnalysisJobFailRequest request);
}
