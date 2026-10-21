package org.monitoring.catchholebackend.domain.relationship.type;

/** 부모·자녀 및 조상·후손 입력은 확정할 때 선대에서 후대로 향하는 한 방향으로 정규화한다. */
public enum RelationshipKind {
    PARENT_OF,
    CHILD_OF,
    ANCESTOR_OF,
    DESCENDANT_OF,
    SPOUSE,
    SIBLING;

    public boolean isAncestry() {
        return this == ANCESTOR_OF || this == DESCENDANT_OF;
    }

    public boolean isCanonicalLineage() {
        return this == PARENT_OF || this == ANCESTOR_OF;
    }

    public boolean isInverseLineage() {
        return this == CHILD_OF || this == DESCENDANT_OF;
    }

    public RelationshipKind canonicalKind() {
        return this == CHILD_OF ? PARENT_OF : this == DESCENDANT_OF ? ANCESTOR_OF : this;
    }
}
