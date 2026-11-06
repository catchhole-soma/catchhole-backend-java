package org.monitoring.catchholebackend.domain.analysis.event;

import java.util.UUID;

/**
 * #387 자동 누적 format 2 Job이 대상을 읽을 때 지금 확정 설정의 지문이 claim 때와 달랐다(GH201을 우회한 쓰기, 예: legacy 단건 비교
 * EXCLUDE). 읽기를 부른 트랜잭션이 되돌려진 뒤 새 트랜잭션에서 그 실행을 기존 무효화 경로로 닫는다.
 */
public record AnalysisLiveInputChangedEvent(UUID analysisJobId) {
}
