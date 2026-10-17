package org.monitoring.catchholebackend.domain.relationship.type;

/** 현재 설정 출처와 캐릭터 상태로 산출하며 관계의 작품 내 시점과 독립적이다. */
public enum RelationshipValidity {
    VALID,
    REVIEW_REQUIRED,
    ARCHIVED_ENDPOINT,
    WITHDRAWN
}
