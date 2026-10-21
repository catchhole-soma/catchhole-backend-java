package org.monitoring.catchholebackend.domain.relationship.processor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipCandidate;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipValidity;
import org.springframework.stereotype.Component;

/** 명시적으로 공유한 언급 끝점을 포함한 표시 그래프를 검사한다. 이름으로 끝점을 합치지 않는다. */
@Component
public class RelationshipMentionGroupValidator {
    public Map<UUID, List<String>> reviewReasons(RelationshipGraphReader.Graph graph,
            List<RelationshipCandidate> mentions) {
        List<Edge> edges = new ArrayList<>();
        for (var relation : graph.relationships()) {
            // 순환 검토 중인 경로도 사용자 묶기를 통한 우회에 쓰이지 않게 유효 근거를 확인한다.
            var validity = graph.validity().get(relation.getId());
            if (validity != RelationshipValidity.VALID && (validity != RelationshipValidity.REVIEW_REQUIRED
                    || graph.evidence().getOrDefault(relation.getId(), List.of()).stream()
                            .noneMatch(e -> RelationshipGraphReader.isSupporting(e, graph.sources())))) continue;
            edges.add(new Edge("relationship:" + relation.getId(), relation.getSubjectCharacter().getId(),
                    relation.getObjectCharacter().getId(), relation.getKind(), relation.getGenerationGap(),
                    relation.getTemporalScope(), relation.getTimeLabel(), null));
        }
        for (var candidate : mentions) {
            UUID groupId = candidate.getMentionGroup() == null ? null : candidate.getMentionGroup().getId();
            UUID mentionId = UUID.nameUUIDFromBytes((groupId == null ? "mention:" + candidate.getId()
                    : "mention-group:" + groupId).getBytes(StandardCharsets.UTF_8));
            UUID subject = candidate.getSubjectCharacter().getId();
            boolean inverse = candidate.getKind().isInverseLineage();
            edges.add(new Edge("candidate:" + candidate.getId(), inverse ? mentionId : subject,
                    inverse ? subject : mentionId, candidate.getKind().canonicalKind(), candidate.getGenerationGap(),
                    candidate.getTemporalScope(), candidate.getTimeLabel(), groupId));
        }
        Map<UUID, Set<UUID>> lineage = new HashMap<>();
        edges.stream().filter(edge -> edge.kind().isCanonicalLineage()).forEach(edge ->
                lineage.computeIfAbsent(edge.subject(), ignored -> new HashSet<>()).add(edge.object()));
        Set<String> generationConflicts = RelationshipGenerationRules.conflictingKeys(edges.stream()
                .map(edge -> new RelationshipGenerationRules.Constraint(edge.id(), edge.subject(), edge.object(),
                        edge.kind(), edge.gap(), edge.temporalScope(), edge.timeLabel())).toList());
        Map<UUID, Set<String>> result = new HashMap<>();
        for (var edge : edges) {
            if (edge.groupId() == null) continue;
            if (edge.subject().equals(edge.object())) {
                result.computeIfAbsent(edge.groupId(), ignored -> new LinkedHashSet<>()).add("SELF_REFERENCE");
            } else if (edge.kind().isCanonicalLineage() && reaches(lineage, edge.object(), edge.subject())) {
                result.computeIfAbsent(edge.groupId(), ignored -> new LinkedHashSet<>()).add("ANCESTRY_CYCLE");
            }
            if (generationConflicts.contains(edge.id())) {
                result.computeIfAbsent(edge.groupId(), ignored -> new LinkedHashSet<>()).add("GENERATION_GAP_CONFLICT");
            }
        }
        Map<UUID, List<String>> reasons = new HashMap<>();
        result.forEach((id, values) -> reasons.put(id, List.copyOf(values)));
        return Map.copyOf(reasons);
    }

    private boolean reaches(Map<UUID, Set<UUID>> edges, UUID from, UUID target) {
        Set<UUID> visited = new HashSet<>();
        ArrayDeque<UUID> queue = new ArrayDeque<>();
        queue.add(from);
        while (!queue.isEmpty()) {
            UUID current = queue.removeFirst();
            if (current.equals(target)) return true;
            if (visited.add(current)) queue.addAll(edges.getOrDefault(current, Set.of()));
        }
        return false;
    }

    private record Edge(String id, UUID subject, UUID object, RelationshipKind kind, Integer gap,
                        RelationshipTemporalScope temporalScope, String timeLabel, UUID groupId) {}
}
