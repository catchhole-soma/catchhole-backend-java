package org.monitoring.catchholebackend.domain.relationship.service;

import static org.monitoring.catchholebackend.domain.relationship.exception.RelationshipGraphErrorCode.RELATIONSHIP_GRAPH_CONFLICT;

import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Direction;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphEdge;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphEvent;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphRules;
import org.monitoring.catchholebackend.global.exception.AppException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Redirect된 노드의 같은 관계를 읽기 전용으로 묶으며 원본 edge와 동결 binding은 보존한다. */
final class RelationshipGraphSlots {
    private RelationshipGraphSlots() {}

    static List<Slot> project(
            Collection<RelationshipGraphEdge> edges,
            List<RelationshipGraphEvent> events,
            Function<UUID, UUID> resolve,
            Function<String, String> hash) {
        Map<UUID, List<RelationshipGraphEvent>> byEdge = new HashMap<>();
        for (var event : events)
            byEdge.computeIfAbsent(event.getEdgeId(), ignored -> new ArrayList<>()).add(event);
        Map<GroupKey, List<Member>> groups = new LinkedHashMap<>();
        for (var edge : edges.stream().sorted(Comparator.comparing(value -> value.getId().toString())).toList()) {
            List<RelationshipGraphEvent> edgeEvents = byEdge.getOrDefault(edge.getId(), List.of());
            Direction direction = direction(edge, edgeEvents, hash);
            UUID source = resolve.apply(edge.getSourceNodeId()), target = resolve.apply(edge.getTargetNodeId());
            if (source.equals(target)) continue;
            String canonical = RelationshipGraphRules.canonical(
                    source.toString(), target.toString(), edge.getAxis(), edge.getKind(), direction);
            int first = edgeEvents.stream().mapToInt(RelationshipGraphEvent::getEpisodeNo)
                    .min().orElse(Integer.MAX_VALUE);
            groups.computeIfAbsent(new GroupKey(edge.getWorkId(), canonical), ignored -> new ArrayList<>())
                    .add(new Member(edge, source, target, direction, first));
        }
        List<Slot> slots = new ArrayList<>();
        for (var group : groups.values()) {
            group.sort(SURVIVOR);
            Member representative = group.stream().filter(Member::unmoved).findFirst().orElse(group.getFirst());
            List<Member> ordered = new ArrayList<>();
            ordered.add(representative);
            group.stream().filter(member -> member != representative).forEach(ordered::add);
            List<RelationshipGraphEdge> members = new ArrayList<>();
            List<RelationshipGraphEvent> slotEvents = new ArrayList<>();
            Map<UUID, Integer> ranks = new HashMap<>();
            Set<UUID> reversed = new HashSet<>();
            String correction = null;
            boolean suppressed = false;
            long version = 0;
            for (var member : ordered) {
                var edge = member.edge();
                ranks.put(edge.getId(), members.size());
                members.add(edge);
                slotEvents.addAll(byEdge.getOrDefault(edge.getId(), List.of()));
                if (correction == null) correction = edge.getCorrectedSummary();
                suppressed |= edge.isSuppressed();
                version = Math.addExact(version, edge.getVersion());
                if (member.direction() != Direction.DIRECTED
                        && !member.source().equals(representative.source())) reversed.add(edge.getId());
            }
            slots.add(new Slot(representative.edge(), members, representative.source(), representative.target(),
                    representative.direction(), correction, suppressed, version, slotEvents, ranks, reversed));
        }
        slots.sort(Comparator.comparing(slot -> slot.representative().getId().toString()));
        return List.copyOf(slots);
    }

    private static Direction direction(RelationshipGraphEdge edge, List<RelationshipGraphEvent> events,
            Function<String, String> hash) {
        Direction found = null;
        for (Direction candidate : Direction.values()) {
            String key = hash.apply(RelationshipGraphRules.canonical(edge.getSourceNodeId().toString(),
                    edge.getTargetNodeId().toString(), edge.getAxis(), edge.getKind(), candidate));
            if (Objects.equals(key, edge.getCanonicalKey())) {
                if (found != null) throw new AppException(RELATIONSHIP_GRAPH_CONFLICT);
                found = candidate;
            }
        }
        if (found == null) throw new AppException(RELATIONSHIP_GRAPH_CONFLICT);
        for (var event : events)
            if (event.getDirection() != found) throw new AppException(RELATIONSHIP_GRAPH_CONFLICT);
        return found;
    }

    private record GroupKey(UUID workId, String canonical) {}

    private record Member(RelationshipGraphEdge edge, UUID source, UUID target, Direction direction,
            int firstEpisodeNo) {
        boolean unmoved() {
            return source.equals(edge.getSourceNodeId()) && target.equals(edge.getTargetNodeId());
        }
    }

    // GH391 survivor 선택과 같고, cutoff/현재 유효성에 따라 공개 식별자가 바뀌지 않는다.
    private static final Comparator<Member> SURVIVOR = Comparator.comparingInt(Member::firstEpisodeNo)
            .thenComparing(member -> member.edge().getCreatedAt(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(member -> member.edge().getId().toString());

    record Slot(
            RelationshipGraphEdge representative,
            List<RelationshipGraphEdge> members,
            UUID source,
            UUID target,
            Direction direction,
            String correctedSummary,
            boolean suppressed,
            long version,
            List<RelationshipGraphEvent> events,
            Map<UUID, Integer> memberRanks,
            Set<UUID> reversedEdgeIds) {
        Slot {
            members = List.copyOf(members);
            events = List.copyOf(events);
            memberRanks = Map.copyOf(memberRanks);
            reversedEdgeIds = Set.copyOf(reversedEdgeIds);
        }

        RelationshipGraphEvent latest(Set<UUID> validAnalysisIds, int cutoff) {
            Comparator<RelationshipGraphEvent> latestFirst =
                    Comparator.comparingInt(RelationshipGraphEvent::getEpisodeNo).reversed()
                            .thenComparingInt(event -> memberRanks.get(event.getEdgeId()))
                            .thenComparing(RelationshipGraphEvent::getCreatedAt,
                                    Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(event -> event.getId().toString(), Comparator.reverseOrder());
            return events.stream().filter(event -> validAnalysisIds.contains(event.getAnalysisId())
                            && event.getEpisodeNo() <= cutoff)
                    .min(latestFirst).orElse(null);
        }

        int knownFrom(Set<UUID> validAnalysisIds, int cutoff) {
            return events.stream().filter(event -> validAnalysisIds.contains(event.getAnalysisId())
                            && event.getEpisodeNo() <= cutoff)
                    .mapToInt(RelationshipGraphEvent::getEpisodeNo).min().orElse(0);
        }

        String sourceName(RelationshipGraphEvent event) {
            requireMember(event);
            return reversedEdgeIds.contains(event.getEdgeId()) ? event.getTargetName() : event.getSourceName();
        }

        String targetName(RelationshipGraphEvent event) {
            requireMember(event);
            return reversedEdgeIds.contains(event.getEdgeId()) ? event.getSourceName() : event.getTargetName();
        }

        private void requireMember(RelationshipGraphEvent event) {
            if (!memberRanks.containsKey(event.getEdgeId())) throw new AppException(RELATIONSHIP_GRAPH_CONFLICT);
        }
    }
}
