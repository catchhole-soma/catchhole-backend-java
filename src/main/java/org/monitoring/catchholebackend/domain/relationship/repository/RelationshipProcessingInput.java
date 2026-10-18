package org.monitoring.catchholebackend.domain.relationship.repository;

import java.util.UUID;

/** 대기 조회 시점의 입력 세대. 처리와 별도 실패 기록에 같은 세대를 전달한다. */
public record RelationshipProcessingInput(UUID workId, long inputVersion) {
}
