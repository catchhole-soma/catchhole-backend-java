package org.monitoring.catchholebackend.domain.relationship.processor;

import java.util.List;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipAssertion;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;

/** 현재 프로필에 적힌 상대 호칭과 명제다. 실제 캐릭터 ID 연결은 호출자가 별도로 검증한다. */
public record ParsedFamilyRelationship(
        String objectMention,
        RelationshipKind kind,
        RelationshipSubtype subtype,
        RelationshipTemporalScope temporalScope,
        String timeLabel,
        RelationshipAssertion assertion,
        List<String> reviewReasons
) {
    public ParsedFamilyRelationship {
        reviewReasons = List.copyOf(reviewReasons);
    }
}
