package org.monitoring.catchholebackend.domain.relationship.processor;

import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Axis;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Direction;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Kind;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * GH391: 이미 저장된 관계도를 작품 단위로 한 번 정리하는 순수 계획기다. 저장소·Spring·엔티티를 모르며 scalar 행만 받는다.
 *
 * <ol>
 *   <li>같은 ACTIVE 프로필에 연결된 노드가 둘 이상이면 대표 노드를 하나 고르고, 나머지 중 이름이 그 프로필로만
 *       엄격하게 해소되고(정확∪포함 충돌 없음, 확정 이름이 뒷받침) 대표 이름의 짧은/긴 형태이며 작품에서 유일한 이름인
 *       노드만 대표 노드로 합친다. 다른 사람이 데이터 오류로 같은 프로필에 붙은 경우를 합치지 않기 위해서다.
 *   <li>미연결 노드는 다음 반영과 같은 {@link RelationshipNameRules#resolve} 결과로 프로필 하나에 해소되면, 그 프로필
 *       노드가 없으면 연결하고 하나면 합친다(둘 이상이면 건너뛴다).
 *   <li>모든 선의 끝점을 해소한 노드로 옮기고 canonical key를 다시 계산한다. 방향은 저장 key에서 복원한다. 같은 slot이
 *       되는 선은 하나만 남기고 정정·삭제 억제·version과 event를 생존 선으로 모은다. 자기 자신을 잇게 되는 선은 지운다.
 * </ol>
 *
 * 연결·병합 회차는 정리 기준 회차(cutoff)다. 노드 행·이름·첫 회차는 바꾸지 않아 동결 bindings와 미래 이름 금지를 지킨다.
 */
public final class RelationshipGraphCleanupRules {
    /** {@code RelationshipGraphServiceImpl.NodeIndex}와 같은 redirect 깊이 상한. */
    static final int MAX_REDIRECT_DEPTH = 8;

    private RelationshipGraphCleanupRules() {}

    public record NodeRow(
            UUID id,
            String name,
            UUID characterId,
            Integer characterLinkedEpisodeNo,
            int firstEpisodeNo,
            UUID mergedIntoNodeId,
            LocalDateTime createdAt) {}

    public record EdgeRow(
            UUID id,
            UUID sourceNodeId,
            UUID targetNodeId,
            Axis axis,
            Kind kind,
            String canonicalKey,
            boolean suppressed,
            String correctedSummary,
            long version,
            LocalDateTime createdAt) {}

    public record EventSlot(UUID id, UUID edgeId, UUID analysisId, int episodeNo, Direction direction) {}

    /**
     * @param confirmed 확정 이름 → 프로필(cutoff 이하, ACTIVE). ordered 작품이면 ACTIVE 대표 이름도 들어 있다.
     * @param profiles 충돌 확인용 ACTIVE 프로필 대표 이름
     * @param hasher canonical key 해시(운영과 같은 SHA-256 소문자 hex)
     */
    public record Input(
            int cutoff,
            List<NodeRow> nodes,
            List<EdgeRow> edges,
            List<EventSlot> events,
            Map<String, ? extends Collection<UUID>> confirmed,
            List<RelationshipNameRules.ProfileName> profiles,
            Function<String, String> hasher) {}

    /** 같은 프로필에 이미 연결된 노드(2-1) 또는 미연결 노드(2-2) 단계. */
    public enum Phase { LINKED, UNLINKED }

    public enum NodeActionKind { LINK, MERGE_UNLINKED, MERGE_LINKED, HELD, SKIPPED }

    public enum Reason {
        /** 이름이 둘 이상의 프로필로 해소된다. */
        HELD_COLLISION,
        /** 이름이 연결된 프로필이 아닌 다른 프로필 하나로 해소된다. */
        OTHER_PROFILE,
        /** 이름이 확정 이름으로 해소되지 않는다. */
        UNRESOLVED,
        /** 이름이 프로필 대표 이름의 짧은/긴 형태가 아니다. */
        NOT_REPRESENTATIVE_FORM,
        /** 같은 이름의 노드가 작품에 둘 이상이다. */
        DUPLICATE_NAME,
        /** 해소된 프로필에 연결 노드가 이미 둘 이상이다. */
        SPLIT_PROFILE,
        /** 연결된 프로필이 보관(ACTIVE 아님) 상태다. 보고만 한다. */
        ARCHIVED_PROFILE,
        /** 노드 이름이 정리 기준 회차 뒤에 처음 나온다. */
        AFTER_CUTOFF
    }

    /**
     * @param profileId 연결(된) 프로필
     * @param targetNodeId 합칠 대표 노드
     * @param resolvedProfileIds 보류·다른 프로필일 때 이름이 해소된 프로필들
     */
    public record NodeAction(
            UUID nodeId,
            String name,
            Phase phase,
            NodeActionKind kind,
            UUID profileId,
            UUID targetNodeId,
            Reason reason,
            List<UUID> resolvedProfileIds) {
        public boolean changes() {
            return kind == NodeActionKind.LINK
                    || kind == NodeActionKind.MERGE_UNLINKED
                    || kind == NodeActionKind.MERGE_LINKED;
        }
    }

    /** 끝점이 바뀌는 생존 선. */
    public record EdgeMove(UUID edgeId, UUID sourceNodeId, UUID targetNodeId, String canonicalKey) {}

    /**
     * 생존 선으로 합쳐 지울 선.
     *
     * @param swapNames 생존 선과 끝점 순서가 반대라 옮기는 event의 source/target 이름을 맞바꾼다
     * @param movedEvents 생존 선으로 옮길 event 수(같은 분석 event에 근거를 합친 것은 제외)
     */
    public record EdgeMerge(UUID edgeId, UUID survivorId, boolean swapNames, int movedEvents) {}

    /** 같은 slot이 된 선 묶음의 보고 행. */
    public record EdgeGroup(
            UUID survivorId,
            List<UUID> mergedEdgeIds,
            boolean moved,
            boolean suppressed,
            boolean corrected,
            boolean correctionConflict) {}

    public record SelfLoop(UUID edgeId, int events) {}

    /** 같은 분석이 같은 slot에 남긴 event: {@code eventId}의 근거를 {@code intoEventId}에 합치고 지운다. */
    public record EvidenceMerge(UUID eventId, UUID intoEventId) {}

    public record Warning(String code, UUID survivorId, UUID edgeId) {}

    public record Plan(
            List<NodeAction> nodes,
            List<EdgeMove> moves,
            List<EdgeMerge> merges,
            List<EdgeGroup> groups,
            List<SelfLoop> selfLoops,
            List<EvidenceMerge> evidenceMerges,
            List<Warning> warnings) {
        public boolean isEmpty() {
            return nodes.stream().noneMatch(NodeAction::changes)
                    && moves.isEmpty()
                    && merges.isEmpty()
                    && selfLoops.isEmpty();
        }

        public long count(NodeActionKind kind) {
            return nodes.stream().filter(n -> n.kind() == kind).count();
        }

        public int movedEvents() {
            return merges.stream().mapToInt(EdgeMerge::movedEvents).sum();
        }

        public int swappedEvents() {
            return merges.stream().filter(EdgeMerge::swapNames).mapToInt(EdgeMerge::movedEvents).sum();
        }

        public int selfLoopEvents() {
            return selfLoops.stream().mapToInt(SelfLoop::events).sum();
        }
    }

    /** 저장 데이터가 계약과 달라 작품 전체를 손대지 않고 멈춘다(예: canonical key 불일치, 끊긴 redirect). */
    public static final class CleanupAbortedException extends RuntimeException {
        private final String code;

        CleanupAbortedException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static Plan plan(Input input) {
        Map<UUID, NodeRow> byId = new LinkedHashMap<>();
        input.nodes().forEach(n -> byId.put(n.id(), n));
        Map<UUID, UUID> mergedInto = new HashMap<>();
        Map<UUID, UUID> linkedTo = new HashMap<>();
        for (NodeRow node : input.nodes()) {
            if (node.mergedIntoNodeId() != null) mergedInto.put(node.id(), node.mergedIntoNodeId());
            else if (node.characterId() != null) linkedTo.put(node.id(), node.characterId());
        }
        // Validate every existing redirect before deciding anything.
        byId.keySet().forEach(id -> root(id, mergedInto, byId));
        Map<String, Long> nameCounts =
                input.nodes().stream()
                        .filter(n -> n.mergedIntoNodeId() == null)
                        .collect(Collectors.groupingBy(NodeRow::name, Collectors.counting()));
        Map<UUID, String> active = new LinkedHashMap<>();
        input.profiles().forEach(p -> active.putIfAbsent(p.characterId(), p.name()));
        List<NodeAction> actions = new ArrayList<>();
        planLinked(input, byId, linkedTo, mergedInto, nameCounts, active, actions);
        planUnlinked(input, byId, linkedTo, mergedInto, nameCounts, actions);
        EdgePlan edges = planEdges(input, byId, mergedInto);
        return new Plan(
                List.copyOf(actions),
                edges.moves(),
                edges.merges(),
                edges.groups(),
                edges.selfLoops(),
                edges.evidenceMerges(),
                edges.warnings());
    }

    /**
     * 정리에 쓰는 확정 이름 목록. ordered 작품은 런타임 ordered prepare가 S0 대표 이름을 확정 근거로 넣는 것과 같이 현재
     * ACTIVE 대표 이름도 넣는다. 아니면 대표 이름은 {@code profiles}로 충돌 확인에만 쓴다.
     */
    public static Map<String, List<UUID>> anchors(
            Map<String, ? extends Collection<UUID>> confirmed,
            List<RelationshipNameRules.ProfileName> profiles,
            boolean ordered) {
        Map<String, List<UUID>> result = new LinkedHashMap<>();
        if (ordered)
            profiles.forEach(p -> result.computeIfAbsent(p.name(), x -> new ArrayList<>()).add(p.characterId()));
        confirmed.forEach((name, ids) -> result.computeIfAbsent(name, x -> new ArrayList<>()).addAll(ids));
        return result;
    }

    /** 2-1: 같은 프로필에 연결된 노드가 둘 이상이면 이름이 그 프로필로만 해소되는 노드만 대표 노드로 합친다. */
    private static void planLinked(
            Input input,
            Map<UUID, NodeRow> byId,
            Map<UUID, UUID> linkedTo,
            Map<UUID, UUID> mergedInto,
            Map<String, Long> nameCounts,
            Map<UUID, String> active,
            List<NodeAction> actions) {
        Map<UUID, List<NodeRow>> byProfile = new TreeMap<>();
        for (NodeRow node : sorted(byId.values()))
            if (linkedTo.containsKey(node.id()))
                byProfile.computeIfAbsent(node.characterId(), x -> new ArrayList<>()).add(node);
        for (var group : byProfile.entrySet()) {
            UUID profile = group.getKey();
            String representative = active.get(profile);
            if (representative == null) {
                // A node linked to an archived profile is reported only; relinking is out of scope.
                for (NodeRow node : group.getValue()) {
                    List<UUID> resolved =
                            RelationshipNameRules.resolveStrictly(node.name(), input.confirmed(), input.profiles());
                    actions.add(new NodeAction(node.id(), node.name(), Phase.LINKED, NodeActionKind.SKIPPED,
                            profile, null,
                            resolved.size() == 1 ? Reason.OTHER_PROFILE : Reason.ARCHIVED_PROFILE, resolved));
                }
                continue;
            }
            List<NodeRow> members = new ArrayList<>();
            for (NodeRow node : group.getValue()) {
                if (node.firstEpisodeNo() <= input.cutoff()) members.add(node);
                else if (group.getValue().size() > 1)
                    actions.add(skip(node, Phase.LINKED, profile, Reason.AFTER_CUTOFF, List.of()));
            }
            if (members.size() < 2) continue;
            String normalizedRepresentative = RelationshipNameRules.normalize(representative);
            Map<UUID, LinkedCheck> checks = new HashMap<>();
            for (NodeRow node : members)
                checks.put(node.id(), checkLinked(node, profile, normalizedRepresentative, input, nameCounts));
            NodeRow canonical = canonicalOf(members, normalizedRepresentative, checks);
            for (NodeRow node : members) {
                if (node == canonical) continue;
                List<UUID> resolved = checks.get(node.id()).resolved();
                Reason reason = checks.get(node.id()).reason();
                if (reason == Reason.HELD_COLLISION) {
                    actions.add(new NodeAction(node.id(), node.name(), Phase.LINKED, NodeActionKind.HELD,
                            profile, null, reason, resolved));
                } else if (reason != null) {
                    actions.add(skip(node, Phase.LINKED, profile, reason, resolved));
                } else {
                    linkedTo.remove(node.id());
                    mergedInto.put(node.id(), canonical.id());
                    actions.add(new NodeAction(node.id(), node.name(), Phase.LINKED, NodeActionKind.MERGE_LINKED,
                            profile, canonical.id(), null, List.of()));
                }
            }
        }
    }

    /** 연결 노드 검사 결과. {@code reason}이 null이면 이름이 그 프로필로만 해소되는 대표 이름의 형태다. */
    private record LinkedCheck(Reason reason, List<UUID> resolved) {}

    private static LinkedCheck checkLinked(
            NodeRow node, UUID profile, String normalizedRepresentative, Input input, Map<String, Long> nameCounts) {
        if (nameCounts.getOrDefault(node.name(), 0L) != 1) return new LinkedCheck(Reason.DUPLICATE_NAME, List.of());
        List<UUID> resolved = RelationshipNameRules.resolveStrictly(node.name(), input.confirmed(), input.profiles());
        Reason reason = null;
        if (resolved.size() > 1) reason = Reason.HELD_COLLISION;
        else if (resolved.isEmpty()) reason = Reason.UNRESOLVED;
        else if (!resolved.getFirst().equals(profile)) reason = Reason.OTHER_PROFILE;
        else if (!RelationshipNameRules.overlaps(RelationshipNameRules.normalize(node.name()), normalizedRepresentative))
            reason = Reason.NOT_REPRESENTATIVE_FORM;
        return new LinkedCheck(reason, resolved);
    }

    /**
     * 대표 이름과 정규화 결과가 같은 노드(여럿이면 가장 먼저 나온 노드). 없으면 검사를 통과한(이름이 그 프로필로만 해소되는 대표
     * 이름의 형태인) 노드 중 가장 먼저 나온 노드. 그런 노드도 없으면 null이고 그 묶음은 합치지 않는다. 데이터 오류로 다른 사람이
     * 먼저 연결돼 있어도 그 노드로 합치지 않기 위해서다.
     */
    private static NodeRow canonicalOf(
            List<NodeRow> members, String normalizedRepresentative, Map<UUID, LinkedCheck> checks) {
        return members.stream()
                .filter(n -> RelationshipNameRules.normalize(n.name()).equals(normalizedRepresentative))
                .min(EARLIEST)
                .orElseGet(() -> members.stream()
                        .filter(n -> checks.get(n.id()).reason() == null)
                        .min(EARLIEST)
                        .orElse(null));
    }

    /** 2-2: 미연결 노드는 다음 반영과 같은 이름 규칙으로 프로필 하나에 해소되면 연결하거나 합친다. */
    private static void planUnlinked(
            Input input,
            Map<UUID, NodeRow> byId,
            Map<UUID, UUID> linkedTo,
            Map<UUID, UUID> mergedInto,
            Map<String, Long> nameCounts,
            List<NodeAction> actions) {
        List<NodeRow> unlinked =
                byId.values().stream()
                        .filter(n -> n.characterId() == null && n.mergedIntoNodeId() == null)
                        .sorted(Comparator.comparingInt(NodeRow::firstEpisodeNo).thenComparing(NodeRow::id))
                        .toList();
        Map<String, List<UUID>> resolved =
                RelationshipNameRules.resolve(
                        input.confirmed(),
                        unlinked.stream().map(NodeRow::name).distinct().toList(),
                        input.profiles());
        for (NodeRow node : unlinked) {
            List<UUID> ids = resolved.get(node.name());
            if (ids == null || ids.isEmpty()) continue;
            ids = ids.stream().distinct().toList();
            if (ids.size() > 1) {
                actions.add(new NodeAction(node.id(), node.name(), Phase.UNLINKED, NodeActionKind.HELD,
                        null, null, Reason.HELD_COLLISION, ids));
                continue;
            }
            UUID profile = ids.getFirst();
            if (node.firstEpisodeNo() > input.cutoff()) {
                actions.add(skip(node, Phase.UNLINKED, profile, Reason.AFTER_CUTOFF, ids));
                continue;
            }
            if (nameCounts.getOrDefault(node.name(), 0L) != 1) {
                actions.add(skip(node, Phase.UNLINKED, profile, Reason.DUPLICATE_NAME, ids));
                continue;
            }
            List<UUID> profileNodes =
                    linkedTo.entrySet().stream()
                            .filter(e -> e.getValue().equals(profile))
                            .map(Map.Entry::getKey)
                            .toList();
            if (profileNodes.isEmpty()) {
                linkedTo.put(node.id(), profile);
                actions.add(new NodeAction(node.id(), node.name(), Phase.UNLINKED, NodeActionKind.LINK,
                        profile, null, null, List.of()));
            } else if (profileNodes.size() == 1) {
                mergedInto.put(node.id(), profileNodes.getFirst());
                actions.add(new NodeAction(node.id(), node.name(), Phase.UNLINKED, NodeActionKind.MERGE_UNLINKED,
                        profile, profileNodes.getFirst(), null, List.of()));
            } else {
                actions.add(skip(node, Phase.UNLINKED, profile, Reason.SPLIT_PROFILE, ids));
            }
        }
    }

    private static NodeAction skip(NodeRow node, Phase phase, UUID profile, Reason reason, List<UUID> resolved) {
        return new NodeAction(node.id(), node.name(), phase, NodeActionKind.SKIPPED, profile, null, reason,
                resolved);
    }

    private record EdgePlan(
            List<EdgeMove> moves,
            List<EdgeMerge> merges,
            List<EdgeGroup> groups,
            List<SelfLoop> selfLoops,
            List<EvidenceMerge> evidenceMerges,
            List<Warning> warnings) {}

    private record Slot(EdgeRow edge, UUID source, UUID target, String key, boolean moved, int firstEpisodeNo) {}

    private static EdgePlan planEdges(Input input, Map<UUID, NodeRow> byId, Map<UUID, UUID> mergedInto) {
        Map<UUID, List<EventSlot>> eventsByEdge = new HashMap<>();
        for (EventSlot event : input.events())
            eventsByEdge.computeIfAbsent(event.edgeId(), x -> new ArrayList<>()).add(event);
        eventsByEdge.values().forEach(list -> list.sort(
                Comparator.comparingInt(EventSlot::episodeNo).thenComparing(EventSlot::id)));
        List<SelfLoop> selfLoops = new ArrayList<>();
        Map<String, List<Slot>> bySlot = new LinkedHashMap<>();
        for (EdgeRow edge : input.edges().stream().sorted(Comparator.comparing(EdgeRow::id)).toList()) {
            List<EventSlot> events = eventsByEdge.getOrDefault(edge.id(), List.of());
            Direction direction = direction(edge, events, input.hasher());
            if (direction == null) throw new CleanupAbortedException("KEY_MISMATCH");
            UUID source = root(edge.sourceNodeId(), mergedInto, byId),
                    target = root(edge.targetNodeId(), mergedInto, byId);
            if (source.equals(target)) {
                selfLoops.add(new SelfLoop(edge.id(), events.size()));
                continue;
            }
            boolean moved = !source.equals(edge.sourceNodeId()) || !target.equals(edge.targetNodeId());
            String key = moved
                    ? input.hasher().apply(RelationshipGraphRules.canonical(
                            source.toString(), target.toString(), edge.axis(), edge.kind(), direction))
                    : edge.canonicalKey();
            int first = events.isEmpty() ? Integer.MAX_VALUE : events.getFirst().episodeNo();
            bySlot.computeIfAbsent(key, x -> new ArrayList<>())
                    .add(new Slot(edge, source, target, key, moved, first));
        }
        List<EdgeMove> moves = new ArrayList<>();
        List<EdgeMerge> merges = new ArrayList<>();
        List<EdgeGroup> groups = new ArrayList<>();
        List<EvidenceMerge> evidenceMerges = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>();
        for (List<Slot> slot : bySlot.values()) {
            List<Slot> kept = slot.stream().filter(s -> !s.moved()).toList();
            // Two unmoved edges cannot share a key (UNIQUE(work_id, canonical_key)).
            if (kept.size() > 1) throw new CleanupAbortedException("DUPLICATE_SLOT");
            List<Slot> ordered = new ArrayList<>(slot);
            ordered.sort(SURVIVOR);
            Slot survivor = kept.isEmpty() ? ordered.getFirst() : kept.getFirst();
            ordered.remove(survivor);
            if (survivor.moved())
                moves.add(new EdgeMove(survivor.edge().id(), survivor.source(), survivor.target(), survivor.key()));
            if (ordered.isEmpty()) continue;
            Map<UUID, UUID> eventByAnalysis = new HashMap<>();
            for (EventSlot event : eventsByEdge.getOrDefault(survivor.edge().id(), List.of()))
                eventByAnalysis.putIfAbsent(event.analysisId(), event.id());
            boolean suppressed = survivor.edge().suppressed();
            String correction = survivor.edge().correctedSummary();
            boolean conflict = false;
            List<UUID> mergedIds = new ArrayList<>();
            for (Slot loser : ordered) {
                int moved = 0;
                for (EventSlot event : eventsByEdge.getOrDefault(loser.edge().id(), List.of())) {
                    UUID into = eventByAnalysis.putIfAbsent(event.analysisId(), event.id());
                    if (into == null) moved++;
                    else evidenceMerges.add(new EvidenceMerge(event.id(), into));
                }
                merges.add(new EdgeMerge(loser.edge().id(), survivor.edge().id(),
                        !loser.source().equals(survivor.source()), moved));
                mergedIds.add(loser.edge().id());
                suppressed |= loser.edge().suppressed();
                String other = loser.edge().correctedSummary();
                if (correction == null) correction = other;
                else if (other != null && !other.equals(correction)) {
                    conflict = true;
                    warnings.add(new Warning("CORRECTION_CONFLICT", survivor.edge().id(), loser.edge().id()));
                }
            }
            groups.add(new EdgeGroup(survivor.edge().id(), List.copyOf(mergedIds), survivor.moved(), suppressed,
                    correction != null, conflict));
        }
        return new EdgePlan(List.copyOf(moves), List.copyOf(merges), List.copyOf(groups), List.copyOf(selfLoops),
                List.copyOf(evidenceMerges), List.copyOf(warnings));
    }

    /**
     * 저장 key와 맞는 방향. event가 없는 선도 key에서 복원하고, event 방향과 어긋나거나(한 선의 event 방향은 하나)
     * 어느 방향으로도 key가 맞지 않으면 null이다.
     */
    static Direction direction(EdgeRow edge, List<EventSlot> events, Function<String, String> hasher) {
        Direction found = null;
        for (Direction candidate : Direction.values()) {
            String key = hasher.apply(RelationshipGraphRules.canonical(
                    edge.sourceNodeId().toString(), edge.targetNodeId().toString(), edge.axis(), edge.kind(),
                    candidate));
            if (key.equals(edge.canonicalKey())) found = candidate;
        }
        Set<Direction> recorded = EnumSet.noneOf(Direction.class);
        events.forEach(e -> recorded.add(e.direction()));
        if (found == null || recorded.size() > 1 || !recorded.isEmpty() && !recorded.contains(found)) return null;
        return found;
    }

    private static UUID root(UUID id, Map<UUID, UUID> mergedInto, Map<UUID, NodeRow> byId) {
        UUID current = id;
        for (int depth = 0; ; depth++) {
            // A redirect is same-work and acyclic; anything else is damaged data, not a guess.
            if (!byId.containsKey(current) || depth > MAX_REDIRECT_DEPTH)
                throw new CleanupAbortedException("REDIRECT_BROKEN");
            UUID next = mergedInto.get(current);
            if (next == null) return current;
            current = next;
        }
    }

    /**
     * 적용 뒤 같은 트랜잭션에서 다시 읽은 행의 사후 검증. 위반 코드별 건수를 돌려주며 비어 있어야 한다.
     * 병합 노드는 미연결, 선의 끝점은 병합되지 않은 노드, 자기 연결 없음, key 재계산 일치, slot 중복 없음.
     */
    public static Map<String, Integer> verify(
            List<NodeRow> nodes, List<EdgeRow> edges, List<EventSlot> events, Function<String, String> hasher) {
        Map<String, Integer> violations = new TreeMap<>();
        Map<UUID, NodeRow> byId = new HashMap<>();
        nodes.forEach(n -> byId.put(n.id(), n));
        for (NodeRow node : nodes)
            if (node.mergedIntoNodeId() != null && node.characterId() != null)
                violations.merge("MERGED_NODE_LINKED", 1, Integer::sum);
        Map<UUID, List<EventSlot>> eventsByEdge =
                events.stream().collect(Collectors.groupingBy(EventSlot::edgeId));
        Set<String> slots = new LinkedHashSet<>();
        for (EdgeRow edge : edges) {
            NodeRow source = byId.get(edge.sourceNodeId()), target = byId.get(edge.targetNodeId());
            if (source == null || target == null || source.mergedIntoNodeId() != null
                    || target.mergedIntoNodeId() != null)
                violations.merge("EDGE_ON_MERGED_NODE", 1, Integer::sum);
            if (edge.sourceNodeId().equals(edge.targetNodeId())) violations.merge("SELF_LOOP", 1, Integer::sum);
            Direction direction = direction(edge, eventsByEdge.getOrDefault(edge.id(), List.of()), hasher);
            if (direction == null) violations.merge("KEY_MISMATCH", 1, Integer::sum);
            if (!slots.add(edge.canonicalKey())) violations.merge("DUPLICATE_SLOT", 1, Integer::sum);
        }
        return violations;
    }

    /** 대표 노드 선정 순서: 첫 회차, 연결 회차, 생성 시각, id. */
    private static final Comparator<NodeRow> EARLIEST =
            Comparator.comparingInt(NodeRow::firstEpisodeNo)
                    .thenComparing(NodeRow::characterLinkedEpisodeNo, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(NodeRow::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(NodeRow::id);

    /** 생존 선 선정 순서(이동하는 선끼리): 첫 event 회차, 생성 시각, id. */
    private static final Comparator<Slot> SURVIVOR =
            Comparator.comparingInt(Slot::firstEpisodeNo)
                    .thenComparing(s -> s.edge().createdAt(), Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(s -> s.edge().id());

    private static List<NodeRow> sorted(Collection<NodeRow> nodes) {
        return nodes.stream().sorted(EARLIEST).toList();
    }
}
