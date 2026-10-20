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
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.relationship.entity.CharacterRelationship;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipCandidate;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipEvidence;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipCandidateRepository;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipEvidenceRepository;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipRepository;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipValidity;
import org.springframework.stereotype.Component;

/** 보관/복구와 설정 수정 직후에도 별도 비동기 갱신 없이 유효성을 다시 계산한다. */
@Component
@RequiredArgsConstructor
public class RelationshipGraphReader {

    private final RelationshipRepository relationshipRepository;
    private final RelationshipCandidateRepository candidateRepository;
    private final RelationshipEvidenceRepository evidenceRepository;
    private final RelationshipSourceResolver sourceResolver;

    public Graph readGraph(UUID workId) {
        List<CharacterRelationship> relationships = relationshipRepository.findAllByWorkId(workId);
        var candidates = relationships.isEmpty() ? List.<RelationshipCandidate>of()
                : candidateRepository.findAllByConfirmedRelationshipIdIn(relationships.stream().map(CharacterRelationship::getId).toList());
        var evidence = candidates.isEmpty() ? List.<RelationshipEvidence>of()
                : evidenceRepository.findAllByCandidateIdIn(candidates.stream().map(RelationshipCandidate::getId).toList());
        Map<UUID, RelationshipSourceContext> sources = sourceResolver.resolve(workId,
                evidence.stream().map(e -> e.getSourceFact().getId()).toList());
        Map<UUID, List<RelationshipEvidence>> byRelation = evidence.stream().collect(Collectors.groupingBy(
                e -> e.getCandidate().getConfirmedRelationship().getId()));
        Map<UUID, RelationshipValidity> validity = new HashMap<>();
        for (CharacterRelationship relation : relationships) {
            RelationshipValidity state;
            if (relation.isWithdrawn()) {
                state = RelationshipValidity.WITHDRAWN;
            } else if (relation.getSubjectCharacter().getStatus() != CharacterStatus.ACTIVE
                    || relation.getObjectCharacter().getStatus() != CharacterStatus.ACTIVE) {
                state = RelationshipValidity.ARCHIVED_ENDPOINT;
            } else {
                state = byRelation.getOrDefault(relation.getId(), List.of()).stream().anyMatch(e -> isSupporting(e, sources))
                        ? RelationshipValidity.VALID : RelationshipValidity.REVIEW_REQUIRED;
            }
            validity.put(relation.getId(), state);
        }
        // 보관 중 추가된 간선이나 근거 복구로 다시 생긴 순환도 화면에 확정 구조로 노출하지 않는다.
        Map<UUID, Set<UUID>> ancestry = ancestry(relationships.stream()
                .filter(r -> validity.get(r.getId()) == RelationshipValidity.VALID).toList());
        for (CharacterRelationship relation : relationships) {
            if (validity.get(relation.getId()) == RelationshipValidity.VALID
                    && relation.getKind().isCanonicalLineage()
                    && reaches(ancestry, relation.getObjectCharacter().getId(), relation.getSubjectCharacter().getId())) {
                validity.put(relation.getId(), RelationshipValidity.REVIEW_REQUIRED);
            }
        }
        var generationConflicts = RelationshipGenerationRules.conflictingKeys(relationships.stream()
                .filter(relation -> validity.get(relation.getId()) == RelationshipValidity.VALID)
                .map(RelationshipGenerationRules::constraint).toList());
        relationships.stream().filter(relation -> generationConflicts.contains(relation.getId().toString()))
                .forEach(relation -> validity.put(relation.getId(), RelationshipValidity.REVIEW_REQUIRED));
        return new Graph(relationships, byRelation, sources, validity);
    }

    public boolean wouldCreateCycle(Graph graph, UUID parent, UUID child) {
        return reaches(ancestry(supportedRelationships(graph)), child, parent);
    }

    public boolean wouldConflictGeneration(Graph graph, RelationshipGenerationRules.Constraint candidate) {
        var constraints = new ArrayList<>(supportedRelationships(graph).stream()
                .map(RelationshipGenerationRules::constraint).toList());
        constraints.add(candidate);
        return RelationshipGenerationRules.conflictingKeys(constraints).contains(candidate.key());
    }

    private List<CharacterRelationship> supportedRelationships(Graph graph) {
        return graph.relationships().stream().filter(r -> !r.isWithdrawn()
                && r.getSubjectCharacter().getStatus() == CharacterStatus.ACTIVE
                && r.getObjectCharacter().getStatus() == CharacterStatus.ACTIVE
                && graph.evidence().getOrDefault(r.getId(), List.of()).stream()
                        .anyMatch(e -> isSupporting(e, graph.sources()))).toList();
    }

    public static boolean isSupporting(RelationshipEvidence evidence, Map<UUID, RelationshipSourceContext> sources) {
        var candidate = evidence.getCandidate();
        return isCurrent(evidence, sources) && !candidate.isAutomaticSupportBlocked()
                && (!candidate.isAutomaticallyConfirmed()
                    || candidate.getValidatedInputVersion() == candidate.getWork().getRelationshipInputVersion());
    }

    public static boolean isCurrent(RelationshipEvidence evidence, Map<UUID, RelationshipSourceContext> sources) {
        RelationshipSourceContext source = sources.get(evidence.getSourceFact().getId());
        return source != null && source.contextToken().equals(evidence.getSourceToken());
    }

    private Map<UUID, Set<UUID>> ancestry(Collection<CharacterRelationship> relationships) {
        Map<UUID, Set<UUID>> adjacency = new HashMap<>();
        relationships.stream().filter(r -> r.getKind().isCanonicalLineage()).forEach(r ->
                adjacency.computeIfAbsent(r.getSubjectCharacter().getId(), ignored -> new HashSet<>())
                        .add(r.getObjectCharacter().getId()));
        return adjacency;
    }

    private boolean reaches(Map<UUID, Set<UUID>> adjacency, UUID from, UUID target) {
        Set<UUID> seen = new HashSet<>();
        ArrayDeque<UUID> queue = new ArrayDeque<>();
        queue.add(from);
        while (!queue.isEmpty()) {
            UUID next = queue.removeFirst();
            if (next.equals(target)) {
                return true;
            }
            if (seen.add(next)) {
                queue.addAll(adjacency.getOrDefault(next, Set.of()));
            }
        }
        return false;
    }

    public record Graph(List<CharacterRelationship> relationships,
                        Map<UUID, List<RelationshipEvidence>> evidence,
                        Map<UUID, RelationshipSourceContext> sources,
                        Map<UUID, RelationshipValidity> validity) {}
}
