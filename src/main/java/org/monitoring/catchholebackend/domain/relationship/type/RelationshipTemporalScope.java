package org.monitoring.catchholebackend.domain.relationship.type;

/** 관계의 작품 내 시점이며, 추출 회차나 서버의 발견 시각과는 구분한다. */
public enum RelationshipTemporalScope {
    CURRENT,
    HISTORICAL,
    UNKNOWN
}
