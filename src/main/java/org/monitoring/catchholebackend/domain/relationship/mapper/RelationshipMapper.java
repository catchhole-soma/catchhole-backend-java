package org.monitoring.catchholebackend.domain.relationship.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.processor.CharacterFactSourceResolver;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipDraft;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipCandidateResponse;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipEvidenceResponse;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipResponse;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipSourceResponse;
import org.monitoring.catchholebackend.domain.relationship.entity.CharacterRelationship;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipCandidate;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipEvidence;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphReader;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipRules;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipSourceContext;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipValidity;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RelationshipMapper {

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final CharacterFactSourceResolver factSourceResolver;

    public RelationshipCandidate toEntity(Work work, UUID requestId, String hash, RelationshipDraft draft,
            WorkCharacter subject, WorkCharacter object, UUID replacedId) {
        return RelationshipCandidate.create(work, requestId, hash, subject, object,
                RelationshipRules.clean(draft.objectMention()), draft.kind(), draft.subtype(),
                draft.temporalScope(), RelationshipRules.clean(draft.timeLabel()), draft.assertion(), replacedId);
    }

    public CharacterRelationship toEntity(RelationshipCandidate candidate,
            RelationshipRules.CanonicalRelationship canonical) {
        WorkCharacter subject = candidate.getSubjectCharacter().getId().equals(canonical.subjectId())
                ? candidate.getSubjectCharacter() : candidate.getObjectCharacter();
        WorkCharacter object = candidate.getSubjectCharacter().getId().equals(canonical.objectId())
                ? candidate.getSubjectCharacter() : candidate.getObjectCharacter();
        return CharacterRelationship.create(candidate.getWork(), subject, object, canonical.kind(),
                candidate.getSubtype(), candidate.getTemporalScope(), candidate.getTimeLabel(), canonical.key());
    }

    public RelationshipSourceResponse toResponse(RelationshipSourceContext source) {
        return new RelationshipSourceResponse(source.factId(), source.characterId(), source.factKey(),
                jsonMapper.convertValue(source.currentValue(), Object.class), source.contextToken(),
                source.hasEvidence(), source.manuallyEdited());
    }

    public RelationshipEvidenceResponse toResponse(RelationshipEvidence evidence,
            Map<UUID, RelationshipSourceContext> sources) {
        CharacterFact fact = evidence.getSourceFact();
        RelationshipSourceContext source = sources.get(fact.getId());
        boolean hasEvidence = fact.getWorkCharacter().getStatus() == CharacterStatus.ACTIVE
                && factSourceResolver.hasEvidence(fact);
        return new RelationshipEvidenceResponse(evidence.getCandidate().getId(), fact.getId(),
                fact.getWorkCharacter().getId(), fact.getFactKey(), evidence.getSourceToken(),
                RelationshipGraphReader.isCurrent(evidence, sources), hasEvidence,
                source != null ? source.manuallyEdited() : fact.getSettingCandidate() == null,
                hasEvidence ? "/api/v1/works/" + evidence.getCandidate().getWork().getId()
                        + "/character-facts/" + fact.getId() + "/evidence" : null,
                RelationshipGraphReader.isCurrent(evidence, sources)
                        ? jsonMapper.convertValue(source.currentValue(), Object.class) : null);
    }

    public RelationshipCandidateResponse toResponse(RelationshipCandidate candidate,
            List<RelationshipEvidence> evidence, Map<UUID, RelationshipSourceContext> sources,
            List<String> reasons) {
        return new RelationshipCandidateResponse(candidate.getId(), candidate.getVersion(), candidate.getStatus(),
                candidate.getSubjectCharacter().getId(),
                candidate.getObjectCharacter() == null ? null : candidate.getObjectCharacter().getId(),
                candidate.getObjectMention(), candidate.getKind(), candidate.getSubtype(), candidate.getTemporalScope(),
                candidate.getTimeLabel(), candidate.getAssertion(),
                candidate.getConfirmedRelationship() == null ? null : candidate.getConfirmedRelationship().getId(),
                reasons, evidence.stream().map(e -> toResponse(e, sources)).toList(), candidate.getCreatedAt(),
                candidate.getOrigin(), candidate.isAutomaticallyConfirmed(), candidate.getSubjectCharacter().getName(),
                candidate.getObjectCharacter() == null ? null : candidate.getObjectCharacter().getName());
    }

    public RelationshipResponse toResponse(CharacterRelationship relation, RelationshipGraphReader.Graph graph) {
        RelationshipValidity validity = graph.validity().get(relation.getId());
        return new RelationshipResponse(relation.getId(), relation.getVersion(),
                relation.getSubjectCharacter().getId(), relation.getObjectCharacter().getId(), relation.getKind(),
                relation.getSubtype(), relation.getTemporalScope(), relation.getTimeLabel(), validity,
                validity == RelationshipValidity.VALID && relation.getTemporalScope() == RelationshipTemporalScope.CURRENT,
                distinctEvidence(graph.evidence().getOrDefault(relation.getId(), List.of())).stream()
                        .map(e -> toResponse(e, graph.sources())).toList(), relation.getCreatedAt(),
                relation.getSubjectCharacter().getName(), relation.getObjectCharacter().getName());
    }

    private List<RelationshipEvidence> distinctEvidence(List<RelationshipEvidence> evidence) {
        Map<String, RelationshipEvidence> unique = new java.util.LinkedHashMap<>();
        evidence.stream().sorted(java.util.Comparator.comparing(e -> e.getCandidate().getId().toString()))
                .forEach(e -> unique.putIfAbsent(e.getSourceFact().getId() + ":" + e.getSourceToken(), e));
        return List.copyOf(unique.values());
    }
}
