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
import java.util.List;
import org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureDiagnostics;

/** LLM 제안에 권한을 부여하지 않고, 고정 입력에 실제 있던 주체·관계·출처만 허용한다. */
@Component
public class RelationshipGraphRules {
    public static final String PREDECESSOR_INCOMPLETE = "RELATIONSHIP_PREDECESSOR_INCOMPLETE";
    public static boolean isPartialFailureReason(String reason) {
        return PREDECESSOR_INCOMPLETE.equals(reason)
                || reason != null && reason.startsWith("RELATIONSHIP_")
                && !"RELATIONSHIP_CONTEXT_INVALID".equals(reason)
                && AnalysisFailureDiagnostics.REASONS.contains(reason);
    }

    /** Identity only: direction/axis/kind remain inside one comparison group. */
    public static String pair(String source, String target) {
        return source.compareTo(target) < 0 ? source + "|" + target : target + "|" + source;
    }

    public static String pair(ContextObservation observation) {
        if (observation.sourceNodeRefs().size() != 1 || observation.targetNodeRefs().size() != 1)
            return null;
        String source = observation.sourceNodeRefs().getFirst(), target = observation.targetNodeRefs().getFirst();
        return source.equals(target) ? null : pair(source, target);
    }
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
        Set<String> failed = validateFailures(context, request);
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
                if (!observations.containsKey(ref) || failed.contains(ref)) invalid();
                covered.add(ref);
            }
            if (decision.operation() == Operation.HOLD
                    || decision.operation() == Operation.EXCLUDE) {
                if (context.supportsPartialResults()) {
                    Set<String> pairs = new HashSet<>();
                    decision.observationRefs().forEach(ref -> pairs.add(pair(observations.get(ref))));
                    if (pairs.contains(null)) {
                        // Unresolved evidence cannot consume another observation's identity or a
                        // historical hold. Keep each new unresolved observation independently.
                        if (decision.operation() != Operation.HOLD || decision.observationRefs().size() != 1
                                || !decision.observationRefs().getFirst().startsWith("O")) invalid();
                    } else if (pairs.size() != 1) invalid();
                }
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
        if (context.observations().stream().anyMatch(o -> !covered.contains(o.ref()) && !failed.contains(o.ref()))) invalid();
    }

    private Set<String> validateFailures(Context context, Complete request) {
        if ((!context.supportsPartialResults() && !request.failures().isEmpty())
                || request.failures().size() > 384) invalid();
        Map<String, Set<String>> groups = new LinkedHashMap<>();
        for (var observation : context.observations()) {
            String pair = pair(observation);
            if (pair != null) groups.computeIfAbsent(pair, key -> new HashSet<>()).add(observation.ref());
        }
        Set<String> blocked = new HashSet<>();
        Set<String> nodes = new HashSet<>();
        context.nodes().forEach(node -> nodes.add(node.ref()));
        for (var value : context.blockedPairs()) {
            if (!context.supportsPartialResults() || value.sourceNodeRef() == null || value.targetNodeRef() == null
                    || value.sourceNodeRef().equals(value.targetNodeRef())
                    || !nodes.contains(value.sourceNodeRef()) || !nodes.contains(value.targetNodeRef())
                    || value.firstFailedEpisodeNo() < 1 || value.firstFailedEpisodeNo() >= context.sourceEpisodeNo()
                    || !blocked.add(pair(value.sourceNodeRef(), value.targetNodeRef()))) invalid();
        }
        Set<String> failed = new HashSet<>(), failedPairs = new HashSet<>();
        for (var failure : request.failures()) {
            if (failure == null || !isPartialFailureReason(failure.reasonCode())
                    || failure.observationRefs() == null || failure.observationRefs().isEmpty()) invalid();
            Set<String> refs = new HashSet<>(failure.observationRefs());
            if (refs.size() != failure.observationRefs().size()) invalid();
            String failedPair = groups.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(refs)).map(Map.Entry::getKey).findFirst().orElse(null);
            if (failedPair == null || !failedPairs.add(failedPair)
                    || blocked.contains(failedPair) != PREDECESSOR_INCOMPLETE.equals(failure.reasonCode())) invalid();
            failed.addAll(refs);
        }
        for (String blockedPair : blocked) {
            if (groups.containsKey(blockedPair) && !failedPairs.contains(blockedPair)) invalid();
        }
        return failed;
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
