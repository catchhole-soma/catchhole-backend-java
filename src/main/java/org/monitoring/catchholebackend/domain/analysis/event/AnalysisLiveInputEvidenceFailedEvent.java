package org.monitoring.catchholebackend.domain.analysis.event;

import java.util.UUID;

/**
 * #387 자동 누적 format 2가 실행 중 대상을 처음 읽다가 확정 근거 연결 오류(ANALYSIS_CURRENT_EVIDENCE_INVALID)를 만났다.
 * 읽기를 부른 트랜잭션이 되돌려진 뒤 새 트랜잭션에서 그 lease의 Job을 claim 입력 실패와 같은 진단 모양으로 마감한다.
 * failureDiagnostics는 예외 context의 진단 값 그대로다.
 */
public record AnalysisLiveInputEvidenceFailedEvent(UUID analysisJobId, UUID leaseToken, String message, Object failureDiagnostics) {
}
