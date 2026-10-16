package org.monitoring.catchholebackend.domain.relationship.processor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** 현재 프로필 slot의 전체 값과 출처 집합에 결속된 관계 해석 입력이다. */
public record RelationshipSourceContext(
        UUID factId,
        UUID characterId,
        String factKey,
        JsonNode currentValue,
        String contextToken,
        boolean hasEvidence,
        boolean manuallyEdited
) {
}
