package org.monitoring.catchholebackend.domain.relationship.service;

import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipGraphCleanupResponse;

import java.util.UUID;

/** GH391: 이미 저장된 관계도의 별칭·분리 노드와 겹친 선을 작품 단위로 한 번 정리한다. */
public interface RelationshipGraphCleanupService {
    /**
     * {@code apply=false}는 아무것도 쓰지 않고 보고서만 만든다. {@code apply=true}는 Work 잠금 아래에서 진행 중·재개 가능한
     * 관계 분석과 진행 중인 관계 백필이 없을 때만 적용하고, 같은 트랜잭션에서 사후 검증이 어긋나면 전부 되돌린다.
     */
    RelationshipGraphCleanupResponse cleanup(UUID workId, boolean apply);
}
