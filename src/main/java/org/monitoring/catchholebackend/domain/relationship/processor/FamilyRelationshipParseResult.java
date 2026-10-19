package org.monitoring.catchholebackend.domain.relationship.processor;

import java.util.List;

/** 지원하지 않는 가족 서술은 안내만 하고, 그 문장으로 관계 종류나 상대를 추정하지 않는다. */
public record FamilyRelationshipParseResult(
        List<ParsedFamilyRelationship> relationships,
        boolean familyMentioned,
        List<String> reviewReasons
) {
    public FamilyRelationshipParseResult {
        relationships = List.copyOf(relationships);
        reviewReasons = List.copyOf(reviewReasons);
    }
}
