package org.monitoring.catchholebackend.domain.analysis.service;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;

/** 같은 회차 완료 트랜잭션 안에서 검증된 제안을 도메인의 기존 반영 규칙으로 저장한다. */
public interface AnalysisAutomaticApplicationContributor {
    String applicationDomain();
    void applyAutomatically(AnalysisJob job);

    /**
     * GH312 S3: 작가가 결정을 저장한 후보가 속한 사람의 그룹 확정 단위(반영 순서대로). 같은 그룹의 결정 없는 후보도 포함하며 상태를 바꾸지 않는다.
     */
    List<List<UUID>> reviewedDecisionGroups(AnalysisJob job);

    /**
     * GH312 S3: 회차 전체 확정이 같은 트랜잭션에서 호출한다. 위 그룹을 공개 그룹 확정의 검증·반영 본문으로 반영하며
     * 검증 실패와 재비교 필요를 그대로 던져 트랜잭션 전체가 되돌려지게 한다.
     */
    void applyReviewedDecisions(AnalysisJob job);
}
