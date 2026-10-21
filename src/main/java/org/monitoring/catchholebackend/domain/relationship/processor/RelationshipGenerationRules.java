package org.monitoring.catchholebackend.domain.relationship.processor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.monitoring.catchholebackend.domain.relationship.entity.CharacterRelationship;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;

/** 명시된 세대 차이만 검사한다. 미상 조상의 세대 수와 중간 인물은 계산하거나 생성하지 않는다. */
public final class RelationshipGenerationRules {
    private RelationshipGenerationRules() {}

    public static Constraint constraint(CharacterRelationship relation) {
        return new Constraint(relation.getId().toString(), relation.getSubjectCharacter().getId(),
                relation.getObjectCharacter().getId(), relation.getKind(), relation.getGenerationGap(),
                relation.getTemporalScope(), relation.getTimeLabel());
    }

    /**
     * 같은 명시 시점의 연결 영역에서 parent=1과 알려진 ancestry=N이 양립해야 한다.
     * 서로 다른 경로의 세대 차이가 맞지 않으면 그 영역의 명제를 모두 검토한다.
     * 기존 부모 관계만의 영역에는 새 검증을 소급하지 않는다.
     */
    public static Set<String> conflictingKeys(Collection<Constraint> constraints) {
        Set<String> conflicts = new HashSet<>();
        var byScope = constraints.stream().filter(value -> value.kind().isCanonicalLineage())
                .filter(value -> value.kind() == RelationshipKind.PARENT_OF || value.generationGap() != null)
                .collect(Collectors.groupingBy(value -> new Scope(value.temporalScope(), value.timeLabel())));
        for (var scoped : byScope.values()) {
            Map<UUID, List<Step>> adjacent = new HashMap<>();
            for (var value : scoped) {
                long gap = value.kind() == RelationshipKind.PARENT_OF ? 1 : value.generationGap();
                adjacent.computeIfAbsent(value.subject(), ignored -> new ArrayList<>())
                        .add(new Step(value.object(), gap, value));
                adjacent.computeIfAbsent(value.object(), ignored -> new ArrayList<>())
                        .add(new Step(value.subject(), -gap, value));
            }
            Set<UUID> visited = new HashSet<>();
            for (UUID start : adjacent.keySet()) {
                if (!visited.add(start)) continue;
                Map<UUID, Long> generations = new HashMap<>();
                Set<String> componentKeys = new HashSet<>();
                ArrayDeque<UUID> queue = new ArrayDeque<>();
                generations.put(start, 0L);
                queue.add(start);
                boolean inconsistent = false;
                boolean explicitAncestry = false;
                while (!queue.isEmpty()) {
                    UUID current = queue.removeFirst();
                    for (var step : adjacent.getOrDefault(current, List.of())) {
                        componentKeys.add(step.constraint().key());
                        explicitAncestry |= step.constraint().kind() == RelationshipKind.ANCESTOR_OF;
                        long expected = generations.get(current) + step.distance();
                        Long previous = generations.putIfAbsent(step.target(), expected);
                        if (previous != null && previous != expected) inconsistent = true;
                        if (visited.add(step.target())) queue.add(step.target());
                    }
                }
                if (explicitAncestry && inconsistent) conflicts.addAll(componentKeys);
            }
        }
        return conflicts;
    }

    public record Constraint(String key, UUID subject, UUID object, RelationshipKind kind, Integer generationGap,
                             RelationshipTemporalScope temporalScope, String timeLabel) {}
    private record Scope(RelationshipTemporalScope temporalScope, String timeLabel) {}
    private record Step(UUID target, long distance, Constraint constraint) {}
}
