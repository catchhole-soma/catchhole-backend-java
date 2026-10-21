package org.monitoring.catchholebackend.domain.relationship.processor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipCandidate;
import org.monitoring.catchholebackend.domain.relationship.exception.RelationshipErrorCode;
import org.monitoring.catchholebackend.domain.relationship.type.FamilyTreeEdgeSourceType;
import org.monitoring.catchholebackend.domain.relationship.type.FamilyTreeNodeType;
import org.monitoring.catchholebackend.domain.relationship.type.FamilyTreeRole;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipValidity;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;

/** 두 Reader의 표시 가능 결과에 범위·순서·상한만 적용한다. 미등록 인물을 이름으로 합치지 않는다. */
@Component
public class FamilyTreeProjector {
    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 200;
    private static final Comparator<TreeEdge> EDGE_ORDER = Comparator.comparing(TreeEdge::id);
    private static final Comparator<TreeNode> NODE_ORDER = Comparator.comparing(TreeNode::id);

    public Projection projectFamilyTree(Work work, RelationshipGraphReader.Graph graph, WorkCharacter focus,
            boolean includeHistorical, int limit, long pendingCandidateCount) {
        return projectFamilyTree(work, graph, focus, includeHistorical, limit,
                new RelationshipMentionReader.Snapshot(List.of(), pendingCandidateCount));
    }

    public Projection projectFamilyTree(Work work, RelationshipGraphReader.Graph graph, WorkCharacter focus,
            boolean includeHistorical, int limit, RelationshipMentionReader.Snapshot mentions) {
        return projectFamilyTree(work, graph, focus, includeHistorical, limit, mentions, Map.of());
    }

    public Projection projectFamilyTree(Work work, RelationshipGraphReader.Graph graph, WorkCharacter focus,
            boolean includeHistorical, int limit, RelationshipMentionReader.Snapshot mentions,
            Map<String, FamilyTreeRoleResolver.EndpointRoles> roles) {
        if (limit < 1 || limit > MAX_LIMIT) throw new AppException(RelationshipErrorCode.RELATIONSHIP_INVALID_RELATIONSHIP);
        Map<String, TreeNode> allById = new HashMap<>();
        if (focus != null) allById.put(focus.getId().toString(), characterNode(focus));
        List<TreeEdge> available = new ArrayList<>();
        graph.relationships().stream()
                .filter(relation -> graph.validity().get(relation.getId()) == RelationshipValidity.VALID)
                .forEach(relation -> {
                    var subject = relation.getSubjectCharacter();
                    var object = relation.getObjectCharacter();
                    allById.put(subject.getId().toString(), characterNode(subject));
                    allById.put(object.getId().toString(), characterNode(object));
                    var endpointRoles = rolesFor(relation.getKind(), relation.getId().toString(), roles);
                    available.add(new TreeEdge(relation.getId().toString(), relation.getVersion(),
                            subject.getId().toString(), object.getId().toString(), subject.getId(), object.getId(),
                            relation.getKind(), relation.getSubtype(), relation.getTemporalScope(), relation.getTimeLabel(),
                            FamilyTreeEdgeSourceType.RELATIONSHIP, relation.getId(), null, endpointRoles.subjectRole(), endpointRoles.objectRole(), relation.getGenerationGap()));
                });
        for (var candidate : mentions.candidates()) {
            var subject = candidate.getSubjectCharacter();
            String edgeId = mentionId(candidate);
            var group = candidate.getMentionGroup();
            String mentionId = group == null ? edgeId : "mention-group:" + group.getId();
            allById.put(subject.getId().toString(), characterNode(subject));
            allById.put(mentionId, new TreeNode(mentionId, group == null ? candidate.getObjectMention().strip() : group.getName(),
                    FamilyTreeNodeType.MENTION, null, group == null ? candidate.getId() : null,
                    group == null ? null : group.getId(), group == null ? null : group.getVersion(), List.of(candidate.getId())));
            boolean childOf = candidate.getKind().isInverseLineage();
            var endpointRoles = rolesFor(candidate.getKind().canonicalKind(), edgeId, roles);
            available.add(new TreeEdge(edgeId, candidate.getVersion(),
                    childOf ? mentionId : subject.getId().toString(), childOf ? subject.getId().toString() : mentionId,
                    childOf ? null : subject.getId(), childOf ? subject.getId() : null,
                    candidate.getKind().canonicalKind(), candidate.getSubtype(),
                    candidate.getTemporalScope(), candidate.getTimeLabel(), FamilyTreeEdgeSourceType.MENTION, null, candidate.getId(),
                    endpointRoles.subjectRole(), endpointRoles.objectRole(), candidate.getGenerationGap()));
        }
        var eligible = available.stream().filter(edge -> edge.temporalScope() == RelationshipTemporalScope.CURRENT
                || includeHistorical && edge.temporalScope() == RelationshipTemporalScope.HISTORICAL).sorted(EDGE_ORDER).toList();
        String focusNodeId = focus == null ? null : focus.getId().toString();
        var distances = focus == null ? Map.<String, Integer>of() : distancesFrom(eligible, focusNodeId);
        var scoped = eligible.stream().filter(edge -> focus == null || distances.containsKey(edge.subjectNodeId()))
                .sorted(focus == null ? EDGE_ORDER : Comparator.<TreeEdge>comparingInt(edge ->
                        Math.min(distances.get(edge.subjectNodeId()), distances.get(edge.objectNodeId())))
                        .thenComparing(EDGE_ORDER)).toList();
        var allNodes = nodes(scoped, allById, focusNodeId);
        var displayed = scoped.stream().limit(limit).toList();
        UUID focusId = focus == null ? null : focus.getId();
        long optionalLinkCount = mentions.candidates().size();
        return new Projection(nodes(displayed, allById, focusNodeId), displayed, allNodes.size(), scoped.size(),
                mentions.pendingCandidateCount(), scoped.size() > limit, limit, includeHistorical, focusId,
                revision(work, allNodes, scoped, focusId, includeHistorical, limit, mentions.pendingCandidateCount(), optionalLinkCount),
                optionalLinkCount);
    }

    private TreeNode characterNode(WorkCharacter character) {
        return new TreeNode(character.getId().toString(), character.getName(), FamilyTreeNodeType.CHARACTER, character.getId(), null, null, null, List.of());
    }

    private FamilyTreeRoleResolver.EndpointRoles rolesFor(RelationshipKind kind, String id,
            Map<String, FamilyTreeRoleResolver.EndpointRoles> roles) {
        return kind == RelationshipKind.PARENT_OF ? roles.getOrDefault(id, FamilyTreeRoleResolver.EndpointRoles.generic())
                : new FamilyTreeRoleResolver.EndpointRoles(null, null);
    }

    private String mentionId(RelationshipCandidate candidate) {
        return "mention:" + candidate.getId();
    }

    private Map<String, Integer> distancesFrom(List<TreeEdge> relationships, String focusId) {
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (var relation : relationships) {
            adjacency.computeIfAbsent(relation.subjectNodeId(), ignored -> new HashSet<>()).add(relation.objectNodeId());
            adjacency.computeIfAbsent(relation.objectNodeId(), ignored -> new HashSet<>()).add(relation.subjectNodeId());
        }
        Map<String, Integer> distances = new HashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        distances.put(focusId, 0);
        queue.add(focusId);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String next : adjacency.getOrDefault(current, Set.of())) {
                if (distances.putIfAbsent(next, distances.get(current) + 1) == null) queue.addLast(next);
            }
        }
        return distances;
    }

    private List<TreeNode> nodes(List<TreeEdge> edges, Map<String, TreeNode> allById, String focusId) {
        Map<String, TreeNode> byId = new LinkedHashMap<>();
        if (focusId != null) byId.put(focusId, allById.get(focusId));
        for (var edge : edges) {
            byId.put(edge.subjectNodeId(), allById.get(edge.subjectNodeId()));
            byId.put(edge.objectNodeId(), allById.get(edge.objectNodeId()));
        }
        Map<String, Set<UUID>> displayedCandidates = new HashMap<>();
        for (var edge : edges) {
            if (edge.sourceType() != FamilyTreeEdgeSourceType.MENTION) continue;
            String mentionId = allById.get(edge.subjectNodeId()).nodeType() == FamilyTreeNodeType.MENTION
                    ? edge.subjectNodeId() : edge.objectNodeId();
            displayedCandidates.computeIfAbsent(mentionId, ignored -> new HashSet<>()).add(edge.candidateId());
        }
        return byId.values().stream().map(node -> node.nodeType() != FamilyTreeNodeType.MENTION ? node
                : new TreeNode(node.id(), node.name(), node.nodeType(), node.characterId(), node.candidateId(),
                        node.mentionGroupId(), node.mentionGroupVersion(), displayedCandidates.getOrDefault(node.id(), Set.of())
                                .stream().sorted(Comparator.comparing(UUID::toString)).toList()))
                .sorted(NODE_ORDER).toList();
    }

    private String revision(Work work, List<TreeNode> nodes, List<TreeEdge> edges, UUID focusId,
            boolean includeHistorical, int limit, long pendingCandidateCount, long optionalLinkCount) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, "family-tree-v5", work.getId(), work.getRelationshipInputVersion(), focusId,
                    includeHistorical, limit, pendingCandidateCount, optionalLinkCount, nodes.size(), edges.size());
            for (var node : nodes) add(digest, node.id(), node.name(), node.nodeType(), node.characterId(), node.candidateId(),
                    node.mentionGroupId(), node.mentionGroupVersion(), node.candidateIds());
            for (var edge : edges) {
                add(digest, edge.id(), edge.version(), edge.subjectNodeId(), edge.objectNodeId(), edge.subjectCharacterId(),
                        edge.objectCharacterId(), edge.kind(), edge.subtype(), edge.temporalScope(), edge.timeLabel(),
                        edge.sourceType(), edge.relationshipId(), edge.candidateId(), edge.subjectRole(), edge.objectRole(), edge.generationGap());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private void add(MessageDigest digest, Object... values) {
        for (Object value : values) {
            byte[] bytes = value == null ? null : value.toString().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
            if (bytes != null) digest.update(bytes);
        }
    }

    public record TreeNode(String id, String name, FamilyTreeNodeType nodeType, UUID characterId, UUID candidateId,
                           UUID mentionGroupId, Long mentionGroupVersion, List<UUID> candidateIds) {}
    public record TreeEdge(String id, long version, String subjectNodeId, String objectNodeId,
                           UUID subjectCharacterId, UUID objectCharacterId, RelationshipKind kind,
                           RelationshipSubtype subtype, RelationshipTemporalScope temporalScope, String timeLabel,
                           FamilyTreeEdgeSourceType sourceType, UUID relationshipId, UUID candidateId,
                           FamilyTreeRole subjectRole, FamilyTreeRole objectRole, Integer generationGap) {}
    public record Projection(List<TreeNode> nodes, List<TreeEdge> edges, int totalNodeCount,
                             int totalEdgeCount, long pendingCandidateCount, boolean truncated, int limit,
                             boolean includeHistorical, UUID focusCharacterId, String revision, long optionalLinkCount) {}
}
