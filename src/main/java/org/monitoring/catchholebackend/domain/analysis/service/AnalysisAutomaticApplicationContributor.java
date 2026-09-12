package org.monitoring.catchholebackend.domain.analysis.service;

import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;

/** 같은 회차 완료 트랜잭션 안에서 검증된 제안을 도메인의 기존 반영 규칙으로 저장한다. */
public interface AnalysisAutomaticApplicationContributor {
    String applicationDomain();
    void applyAutomatically(AnalysisJob job);
}
