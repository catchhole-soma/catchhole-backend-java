package org.monitoring.catchholebackend.domain.relationship.processor;

import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Axis;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Complete;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Context;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.ContextObservation;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.ContextRelationship;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Decision;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Direction;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.EdgeStatus;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Endpoint;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Evidence;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Kind;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Observation;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Operation;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Prepare;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.TemporalScope;
import static org.monitoring.catchholebackend.domain.relationship.exception.RelationshipGraphErrorCode.RELATIONSHIP_GRAPH_INVALID_INPUT;

import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** LLM 제안에 권한을 부여하지 않고, 고정 입력에 실제 있던 주체·관계·출처만 허용한다. */
@Component
public class RelationshipGraphRules {
    public void validatePrepare(Prepare request, String source) {
        if (request == null
                || request.formatVersion() != 1
                || request.sourceHash() == null
                || request.observations() == null
                || request.observations().size() > 384
                || request.rejectedObservationCount() != null
                        && request.rejectedObservationCount() < 0) invalid();
        Set<String> refs = new HashSet<>();
        int sourceLength = source.codePointCount(0, source.length());
        for (Observation observation : request.observations()) {
            if (observation == null
                    || observation.ref() == null
                    || !observation.ref().matches("O[1-9][0-9]*")
                    || !refs.add(observation.ref())
                    || !text(observation.summary(), 300)
                    || observation.evidence() == null
                    || observation.evidence().isEmpty()
                    || observation.evidence().size() > 12) invalid();
            validateEndpoint(observation.source(), source);
            validateEndpoint(observation.target(), source);
            int size = 0;
            for (Evidence evidence : observation.evidence()) {
                if (evidence == null
                        || evidence.quote() == null
                        || evidence.quote().isBlank()
                        || evidence.startOffset() < 0
                        || evidence.endOffset() <= evidence.startOffset()
                        || evidence.endOffset() > sourceLength
                        || evidence.endOffset() - evidence.startOffset() > 480) invalid();
                String exact =
                        source.substring(
                                source.offsetByCodePoints(0, evidence.startOffset()),
                                source.offsetByCodePoints(0, evidence.endOffset()));
                if (!exact.equals(evidence.quote())) invalid();
                size += evidence.endOffset() - evidence.startOffset();
            }
            if (size > 4000
                    || observation.evidence().stream()
                            .noneMatch(e -> e.quote().contains(observation.source().mention()))
                    || observation.evidence().stream()
                            .noneMatch(e -> e.quote().contains(observation.target().mention())))
                invalid();
        }
    }

    private void validateEndpoint(Endpoint endpoint, String source) {
        if (endpoint == null
                || !text(endpoint.mention(), 100)
                || endpoint.name() != null
                        && (!text(endpoint.name(), 100) || !source.contains(endpoint.name())))
            invalid();
    }

    public void validateComplete(Context context, Complete request) {
        if (request == null
                || request.formatVersion() != 1
                || !Objects.equals(context.contextToken(), request.contextToken())
                || request.decisions() == null
                || request.decisions().size() > 512) invalid();
        Map<String, ContextObservation> observations = new LinkedHashMap<>();
        context.observations().forEach(o -> observations.put(o.ref(), o));
        context.heldObservations().forEach(o -> observations.put(o.ref(), o));
        Map<String, ContextRelationship> relationships = new HashMap<>();
        context.relationships().forEach(r -> relationships.put(r.ref(), r));
        Set<String> covered = new HashSet<>(),
                mutated = new HashSet<>(),
                disposition = new HashSet<>(),
                applied = new HashSet<>();
        for (Decision decision : request.decisions()) {
            if (decision == null
                    || decision.operation() == null
                    || decision.temporalScope() == null
                    || decision.observationRefs() == null
                    || decision.observationRefs().isEmpty()
                    || decision.reason() != null && !text(decision.reason(), 500)) invalid();
            if (new HashSet<>(decision.observationRefs()).size()
                            != decision.observationRefs().size()
                    || decision.observationRefs().stream().noneMatch(r -> r.startsWith("O")))
                invalid();
            for (String ref : decision.observationRefs()) {
                if (!observations.containsKey(ref)) invalid();
                covered.add(ref);
            }
            if (decision.operation() == Operation.HOLD
                    || decision.operation() == Operation.EXCLUDE) {
                for (String ref : decision.observationRefs()) {
                    if (applied.contains(ref) || !disposition.add(ref)) invalid();
                }
                if (decision.relationshipRef() != null
                        || decision.sourceNodeRef() != null
                        || decision.targetNodeRef() != null
                        || decision.axis() != null
                        || decision.kind() != null
                        || decision.direction() != null
                        || decision.label() != null
                        || decision.summary() != null
                        || !text(decision.reason(), 500)) invalid();
                continue;
            }
            for (String ref : decision.observationRefs()) {
                if (disposition.contains(ref)) invalid();
                applied.add(ref);
            }
            if (decision.temporalScope() != TemporalScope.PRESENT
                    || decision.axis() == null
                    || decision.kind() == null
                    || decision.direction() == null
                    || !text(decision.label(), 60)
                    || !text(decision.summary(), 500)
                    || decision.sourceNodeRef() == null
                    || decision.targetNodeRef() == null
                    || decision.sourceNodeRef().equals(decision.targetNodeRef())) invalid();
            for (String ref : decision.observationRefs()) {
                ContextObservation observation = observations.get(ref);
                if (observation.sourceNodeRefs().size() != 1
                        || observation.targetNodeRefs().size() != 1) invalid();
                // LLM은 제공하지 않은 인물로 바꾸거나 방향을 근거 없이 반전할 수 없다.
                boolean forward =
                        observation.sourceNodeRefs().contains(decision.sourceNodeRef())
                                && observation.targetNodeRefs().contains(decision.targetNodeRef());
                boolean reverse =
                        observation.sourceNodeRefs().contains(decision.targetNodeRef())
                                && observation.targetNodeRefs().contains(decision.sourceNodeRef());
                if (!forward && !reverse) invalid();
            }
            String pair =
                    canonical(
                            decision.sourceNodeRef(),
                            decision.targetNodeRef(),
                            decision.axis(),
                            decision.kind(),
                            decision.direction());
            if (!mutated.add(pair)) invalid();
            ContextRelationship existing =
                    decision.relationshipRef() == null
                            ? null
                            : relationships.get(decision.relationshipRef());
            if (decision.operation() == Operation.ADD) {
                if (existing != null
                        || decision.relationshipRef() != null
                        || relationships.values().stream()
                                .anyMatch(
                                        r ->
                                                canonical(
                                                                r.sourceNodeRef(),
                                                                r.targetNodeRef(),
                                                                r.axis(),
                                                                r.kind(),
                                                                r.direction())
                                                        .equals(pair))) invalid();
            } else {
                if (existing == null
                        || existing.axis() != decision.axis()
                        || existing.kind() != decision.kind()
                        || !canonical(
                                        existing.sourceNodeRef(),
                                        existing.targetNodeRef(),
                                        existing.axis(),
                                        existing.kind(),
                                        existing.direction())
                                .equals(pair)) invalid();
                if ((existing.direction() != decision.direction()
                        || !existing.sourceNodeRef().equals(decision.sourceNodeRef())
                        || !existing.targetNodeRef().equals(decision.targetNodeRef()))) invalid();
                if (decision.operation() == Operation.END && existing.status() != EdgeStatus.ACTIVE)
                    invalid();
                if (decision.operation() == Operation.MERGE
                        && (existing.status() != EdgeStatus.ACTIVE
                                || !existing.label().equals(decision.label())
                                || !existing.summary().equals(decision.summary()))) invalid();
            }
        }
        if (context.observations().stream().anyMatch(o -> !covered.contains(o.ref()))) invalid();
    }

    public static String canonical(
            String source, String target, Axis axis, Kind kind, Direction direction) {
        return (direction == Direction.DIRECTED || source.compareTo(target) < 0
                        ? source + "|" + target
                        : target + "|" + source)
                + "|"
                + axis
                + "|"
                + kind
                + "|"
                + direction;
    }

    public static boolean text(String value, int maximum) {
        return value != null
                && !value.isBlank()
                && value.equals(value.trim())
                && value.codePointCount(0, value.length()) <= maximum;
    }

    private static void invalid() {
        throw new AppException(RELATIONSHIP_GRAPH_INVALID_INPUT);
    }
}
