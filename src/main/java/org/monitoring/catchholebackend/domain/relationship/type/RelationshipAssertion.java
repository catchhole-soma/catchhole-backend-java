package org.monitoring.catchholebackend.domain.relationship.type;

/** 후보의 주장 성격을 보존하며 ASSERTED 외의 주장을 자동으로 관계로 승격하지 않는다. */
public enum RelationshipAssertion {
    ASSERTED,
    UNCERTAIN,
    NEGATED,
    FIGURATIVE,
    CONFLICTING
}
