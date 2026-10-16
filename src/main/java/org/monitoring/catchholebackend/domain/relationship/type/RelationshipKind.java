package org.monitoring.catchholebackend.domain.relationship.type;

/** 부모·자식 입력은 확정할 때 부모에서 자식으로 향하는 PARENT_OF 한 방향으로 정규화한다. */
public enum RelationshipKind {
    PARENT_OF,
    CHILD_OF,
    SPOUSE,
    SIBLING
}
