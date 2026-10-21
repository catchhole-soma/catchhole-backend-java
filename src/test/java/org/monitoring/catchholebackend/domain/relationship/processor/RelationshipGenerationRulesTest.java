package org.monitoring.catchholebackend.domain.relationship.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;

@DisplayName("명시 조상 세대 수와 이미 알려진 부모 경로의 일관성")
class RelationshipGenerationRulesTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    @Test
    @DisplayName("두 부모 세대와 같은 2대손은 유지하고 5대손은 연결 영역을 검토한다")
    void distinguishesMatchingAndConflictingParentPaths() {
        var ab = edge("ab", a, b, RelationshipKind.PARENT_OF, null);
        var bc = edge("bc", b, c, RelationshipKind.PARENT_OF, null);
        assertThat(RelationshipGenerationRules.conflictingKeys(List.of(ab, bc,
                edge("ac", a, c, RelationshipKind.ANCESTOR_OF, 2)))).isEmpty();
        assertThat(RelationshipGenerationRules.conflictingKeys(List.of(ab, bc,
                edge("ac", a, c, RelationshipKind.ANCESTOR_OF, 5)))).containsExactlyInAnyOrder("ab", "bc", "ac");
    }

    @Test
    @DisplayName("미상 세대는 부모 경로에서 보충하지 않고 제약에서도 제외한다")
    void leavesUnknownGapUnconstrained() {
        assertThat(RelationshipGenerationRules.conflictingKeys(List.of(
                edge("ab", a, b, RelationshipKind.PARENT_OF, null),
                edge("bc", b, c, RelationshipKind.PARENT_OF, null),
                edge("ac", a, c, RelationshipKind.ANCESTOR_OF, null)))).isEmpty();
    }

    @Test
    @DisplayName("역방향 탐색으로 닫히는 세대 차이도 비교하며 큰 값은 정수 오버플로 없이 처리한다")
    void comparesKnownPathsUsingLongOffsets() {
        assertThat(RelationshipGenerationRules.conflictingKeys(List.of(
                edge("ab", a, b, RelationshipKind.ANCESTOR_OF, Integer.MAX_VALUE),
                edge("bc", b, c, RelationshipKind.PARENT_OF, null),
                edge("ac", a, c, RelationshipKind.ANCESTOR_OF, Integer.MAX_VALUE))))
                .containsExactlyInAnyOrder("ab", "bc", "ac");
    }

    @Test
    @DisplayName("서로 다른 명시 시점의 관계는 세대 수 충돌로 합치지 않는다")
    void separatesExplicitTemporalScopes() {
        assertThat(RelationshipGenerationRules.conflictingKeys(List.of(
                edge("ab", a, b, RelationshipKind.ANCESTOR_OF, 5),
                new RelationshipGenerationRules.Constraint("past", a, b, RelationshipKind.ANCESTOR_OF, 7,
                        RelationshipTemporalScope.HISTORICAL, null)))).isEmpty();
    }

    private RelationshipGenerationRules.Constraint edge(String key, UUID subject, UUID object,
            RelationshipKind kind, Integer gap) {
        return new RelationshipGenerationRules.Constraint(key, subject, object, kind, gap,
                RelationshipTemporalScope.CURRENT, null);
    }
}
