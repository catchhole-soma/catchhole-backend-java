package org.monitoring.catchholebackend.domain.analysis.service;

import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobCompleteRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobFailRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobProgressRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobHeartbeatResponse;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobPayload;

public interface AnalysisJobWorkerService {

    /**
     * Worker가 허용한 유형의 대기 작업 하나를 선점하고 lease와 단일 회차 payload를 발급한다.
     * 대기 작업이 없으면 빈 Optional을 반환하며, 대상 회차 계약이 잘못된 Job은 실패 처리한다.
     */
    Optional<WorkerAnalysisJobPayload> claimAnalysisJob(WorkerAnalysisJobClaimRequest request);

    WorkerAnalysisJobHeartbeatResponse heartbeatAnalysisJob(UUID analysisJobId, UUID leaseToken);

    /**
     * lease가 유효한 작업의 표시 단계, 회차 상태와 재시작 checkpoint를 갱신한다.
     */
    void updateProgress(UUID analysisJobId, UUID leaseToken, WorkerAnalysisJobProgressRequest request);

    /**
     * 필수 checkpoint와 세계관 후보 terminal 상태를 검증한 뒤 성공으로 전환한다.
     * 요약 JSON은 Worker 값을 보존하고 토큰 합계는 Backend 정산 원장에서 산출한다.
     */
    void completeAnalysisJob(UUID analysisJobId, UUID leaseToken, WorkerAnalysisJobCompleteRequest request);

    /**
     * 실행 중인 분석 작업을 실패 상태로 전환한다.
     * Worker가 전달한 실패 사유를 저장해 이후 조회 응답에서 원인을 확인할 수 있게 한다.
     */
    void failAnalysisJob(UUID analysisJobId, UUID leaseToken, WorkerAnalysisJobFailRequest request);
}
