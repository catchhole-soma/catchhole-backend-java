package org.monitoring.catchholebackend.domain.analysis.event;

import java.util.UUID;

/** 실행 취소와 같은 트랜잭션에서 남은 토큰 예약을 정리한다. */
public record AnalysisRunInvalidatedEvent(UUID analysisJobId) {
}
