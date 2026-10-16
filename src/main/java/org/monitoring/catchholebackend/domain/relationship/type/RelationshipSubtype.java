package org.monitoring.catchholebackend.domain.relationship.type;

/** 명시되지 않은 혈연·입양 등의 세부 유형은 UNSPECIFIED로 보존한다. */
public enum RelationshipSubtype {
    UNSPECIFIED,
    BIOLOGICAL,
    ADOPTIVE,
    STEP,
    FULL,
    HALF
}
