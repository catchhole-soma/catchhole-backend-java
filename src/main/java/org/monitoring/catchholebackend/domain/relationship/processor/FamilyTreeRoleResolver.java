package org.monitoring.catchholebackend.domain.relationship.processor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.relationship.entity.CharacterRelationship;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipCandidate;
import org.monitoring.catchholebackend.domain.relationship.type.FamilyTreeRole;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipAssertion;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipValidity;
import org.springframework.stereotype.Component;

/** 현재 지지 근거의 명시적 호칭만 끝점에 붙인다. 인물 성별·이름·반대편 호칭으로 보완하지 않는다. */
@Component
@RequiredArgsConstructor
public class FamilyTreeRoleResolver {
    private final ExplicitFamilyRelationshipParser parser;
    private final WorkCharacterRepository characterRepository;

    public Map<String, EndpointRoles> resolveRoles(UUID workId, RelationshipGraphReader.Graph graph,
            RelationshipMentionReader.Snapshot mentions) {
        var names = characterRepository.findAllByWorkIdAndStatusOrderByCreatedAtDesc(workId, CharacterStatus.ACTIVE)
                .stream().collect(Collectors.groupingBy(character -> character.getName().strip(), Collectors.counting()));
        Map<String, EndpointRoles> result = new HashMap<>();
        Map<RelationshipSourceContext, List<ParsedFamilyRelationship>> parsed = new HashMap<>();
        for (var relation : graph.relationships()) {
            if (relation.getKind() != RelationshipKind.PARENT_OF
                    || graph.validity().get(relation.getId()) != RelationshipValidity.VALID) continue;
            List<EndpointRoles> roles = new ArrayList<>();
            for (var evidence : graph.evidence().getOrDefault(relation.getId(), List.of())) {
                if (!RelationshipGraphReader.isSupporting(evidence, graph.sources())) continue;
                var source = graph.sources().get(evidence.getSourceFact().getId());
                for (var claim : parse(source, parsed)) {
                    if (matchesRelationship(source, claim, relation, names)) roles.add(normalize(claim));
                }
            }
            result.put(relation.getId().toString(), combine(roles));
        }
        for (var candidate : mentions.candidates()) {
            if (!isParentKind(candidate.getKind())) continue;
            List<EndpointRoles> roles = new ArrayList<>();
            for (var evidence : mentions.evidence().getOrDefault(candidate.getId(), List.of())) {
                if (!RelationshipGraphReader.isCurrent(evidence, mentions.sources())) continue;
                var source = mentions.sources().get(evidence.getSourceFact().getId());
                for (var claim : parse(source, parsed)) {
                    if (matchesMention(source, claim, candidate)) roles.add(normalize(claim));
                }
            }
            result.put("mention:" + candidate.getId(), combine(roles));
        }
        return Map.copyOf(result);
    }

    private List<ParsedFamilyRelationship> parse(RelationshipSourceContext source,
            Map<RelationshipSourceContext, List<ParsedFamilyRelationship>> parsed) {
        return parsed.computeIfAbsent(source, context -> parser.parse(context.factKey(), context.currentValue()).relationships());
    }

    private boolean matchesRelationship(RelationshipSourceContext source, ParsedFamilyRelationship claim,
            CharacterRelationship relation, Map<String, Long> names) {
        if (!matchesMeaning(claim, relation.getSubtype(), relation.getTemporalScope(), relation.getTimeLabel())) return false;
        UUID owner = source.characterId();
        WorkCharacter other = owner.equals(relation.getSubjectCharacter().getId()) ? relation.getObjectCharacter()
                : owner.equals(relation.getObjectCharacter().getId()) ? relation.getSubjectCharacter() : null;
        if (other == null || !claim.objectMention().equals(other.getName().strip())
                || names.getOrDefault(other.getName().strip(), 0L) != 1) return false;
        UUID parsedParent = claim.kind() == RelationshipKind.PARENT_OF ? owner : other.getId();
        UUID parsedChild = claim.kind() == RelationshipKind.PARENT_OF ? other.getId() : owner;
        return parsedParent.equals(relation.getSubjectCharacter().getId()) && parsedChild.equals(relation.getObjectCharacter().getId());
    }

    private boolean matchesMention(RelationshipSourceContext source, ParsedFamilyRelationship claim, RelationshipCandidate candidate) {
        return source.characterId().equals(candidate.getSubjectCharacter().getId())
                && claim.objectMention().equals(candidate.getObjectMention().strip())
                && claim.kind() == candidate.getKind()
                && matchesMeaning(claim, candidate.getSubtype(), candidate.getTemporalScope(), candidate.getTimeLabel());
    }

    private boolean matchesMeaning(ParsedFamilyRelationship claim, RelationshipSubtype subtype,
            RelationshipTemporalScope scope, String timeLabel) {
        return isParentKind(claim.kind()) && claim.assertion() == RelationshipAssertion.ASSERTED
                && claim.reviewReasons().isEmpty() && claim.subtype() == subtype
                && claim.temporalScope() == scope && Objects.equals(claim.timeLabel(), timeLabel);
    }

    private boolean isParentKind(RelationshipKind kind) {
        return kind == RelationshipKind.PARENT_OF || kind == RelationshipKind.CHILD_OF;
    }

    private EndpointRoles normalize(ParsedFamilyRelationship claim) {
        return claim.kind() == RelationshipKind.CHILD_OF ? new EndpointRoles(claim.objectRole(), claim.subjectRole())
                : new EndpointRoles(claim.subjectRole(), claim.objectRole());
    }

    private EndpointRoles combine(List<EndpointRoles> values) {
        var parentRoles = EnumSet.noneOf(FamilyTreeRole.class);
        var childRoles = EnumSet.noneOf(FamilyTreeRole.class);
        for (var value : values) {
            if (value.subjectRole() == FamilyTreeRole.FATHER || value.subjectRole() == FamilyTreeRole.MOTHER) parentRoles.add(value.subjectRole());
            if (value.objectRole() == FamilyTreeRole.SON || value.objectRole() == FamilyTreeRole.DAUGHTER) childRoles.add(value.objectRole());
        }
        return new EndpointRoles(parentRoles.size() == 1 ? parentRoles.iterator().next() : FamilyTreeRole.PARENT,
                childRoles.size() == 1 ? childRoles.iterator().next() : FamilyTreeRole.CHILD);
    }

    public record EndpointRoles(FamilyTreeRole subjectRole, FamilyTreeRole objectRole) {
        public static EndpointRoles generic() {
            return new EndpointRoles(FamilyTreeRole.PARENT, FamilyTreeRole.CHILD);
        }
    }
}
