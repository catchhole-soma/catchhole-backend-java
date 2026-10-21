package org.monitoring.catchholebackend.domain.relationship.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.relationship.entity.CharacterRelationship;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipCandidate;
import org.monitoring.catchholebackend.domain.relationship.type.FamilyTreeEdgeSourceType;
import org.monitoring.catchholebackend.domain.relationship.type.FamilyTreeNodeType;
import org.monitoring.catchholebackend.domain.relationship.type.FamilyTreeRole;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipAssertion;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipValidity;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("가계도 표시 상한·초점 거리·안정 정렬·변경 감지 규칙")
class FamilyTreeProjectorTest {
    private final FamilyTreeProjector projector = new FamilyTreeProjector();
    private final Work work = Work.create(null, "합성 작품", WorkGenre.FANTASY, null);

    @Test
    @DisplayName("큰 작품의 분리된 가족도 전체 합계는 유지하고 최대 200간선·400인물만 반환한다")
    void boundsDisconnectedFamiliesAndPreservesCompleteCounts() {
        List<CharacterRelationship> edges = new ArrayList<>();
        for (int i = 0; i < 300; i++) edges.add(edge(i + 1, character(i * 2), character(i * 2 + 1)));
        var projection = projector.projectFamilyTree(work, graph(edges), null, false, 200, 37);

        assertThat(projection.edges()).hasSize(200);
        assertThat(projection.nodes()).hasSize(400);
        assertThat(projection.totalEdgeCount()).isEqualTo(300);
        assertThat(projection.totalNodeCount()).isEqualTo(600);
        assertThat(projection.pendingCandidateCount()).isEqualTo(37);
        assertThat(projection.truncated()).isTrue();
        assertThat(projection.nodes()).extracting(FamilyTreeProjector.TreeNode::id).doesNotHaveDuplicates();
        var nodeIds = projection.nodes().stream().map(FamilyTreeProjector.TreeNode::id).toList();
        assertThat(projection.edges()).allSatisfy(edge ->
                assertThat(nodeIds).contains(edge.subjectNodeId(), edge.objectNodeId()));
    }

    @Test
    @DisplayName("초점에서 가까운 간선을 먼저 선택하고 다른 가족·미확정 부모를 만들어 연결하지 않는다")
    void prioritizesDistanceFromFocusBeforeStableId() {
        var focus = character(1);
        var middle = character(2);
        var far = character(3);
        var near = edge(100, focus, middle);
        var distant = edge(1, middle, far);
        var unrelated = edge(2, character(4), character(5));
        var projection = projector.projectFamilyTree(work, graph(List.of(distant, unrelated, near)), focus, false, 1, 0);

        assertThat(projection.edges()).extracting(FamilyTreeProjector.TreeEdge::relationshipId).containsExactly(near.getId());
        assertThat(projection.nodes()).extracting(FamilyTreeProjector.TreeNode::characterId).containsExactly(focus.getId(), middle.getId());
        assertThat(projection.totalEdgeCount()).isEqualTo(2);
        assertThat(projection.totalNodeCount()).isEqualTo(3);
        assertThat(projection.truncated()).isTrue();
    }

    @Test
    @DisplayName("DB 반환 순서가 바뀌어도 간선 선택·노드 순서·revision은 같다")
    void shuffledReadsDoNotReorderOrChangeRevision() {
        var first = edge(3, character(5), character(4));
        var second = edge(1, character(2), character(3));
        var third = edge(2, character(1), character(2));
        List<CharacterRelationship> edges = new ArrayList<>(List.of(first, second, third));
        var before = projector.projectFamilyTree(work, graph(edges), null, false, 2, 0);
        Collections.reverse(edges);
        var after = projector.projectFamilyTree(work, graph(edges), null, false, 2, 0);

        assertThat(after).isEqualTo(before);
        assertThat(after.edges()).extracting(FamilyTreeProjector.TreeEdge::relationshipId).containsExactly(second.getId(), third.getId());
        assertThat(after.nodes()).extracting(FamilyTreeProjector.TreeNode::id).isSorted();
    }

    @Test
    @DisplayName("상한 밖 관계의 근거 버전 변화도 revision에 포함해 같은 개수의 변경을 감지한다")
    void revisionIncludesRelationshipsOutsideDisplayLimit() {
        var first = edge(1, character(1), character(2));
        var hidden = edge(2, character(3), character(4));
        var graph = graph(List.of(first, hidden));
        var before = projector.projectFamilyTree(work, graph, null, false, 1, 0);
        hidden.recordSupportChange();
        var after = projector.projectFamilyTree(work, graph, null, false, 1, 0);

        assertThat(after.edges()).isEqualTo(before.edges());
        assertThat(after.totalEdgeCount()).isEqualTo(before.totalEdgeCount());
        assertThat(after.revision()).isNotEqualTo(before.revision());
    }

    @Test
    @DisplayName("GraphReader가 순환으로 검토 처리한 관계는 가계도 범위에도 포함하지 않는다")
    void respectsGraphValidityInsteadOfReconstructingCycles() {
        var first = edge(1, character(1), character(2));
        var second = edge(2, character(2), character(1));
        var graph = new RelationshipGraphReader.Graph(List.of(first, second), Map.of(), Map.of(),
                Map.of(first.getId(), RelationshipValidity.REVIEW_REQUIRED, second.getId(), RelationshipValidity.REVIEW_REQUIRED));

        var projection = projector.projectFamilyTree(work, graph, null, true, 100, 0);

        assertThat(projection.edges()).isEmpty();
        assertThat(projection.nodes()).isEmpty();
        assertThat(projection.totalEdgeCount()).isZero();
    }

    @Test
    @DisplayName("수백 동명 언급도 후보별 끝점을 보존하고 200간선 상한 밖의 전체 개수를 잃지 않는다")
    void boundsMentionLeavesWithoutMergingSameNamesOrDroppingTotals() {
        List<RelationshipCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 260; i++) candidates.add(mention(i + 1, character(i + 1), RelationshipKind.CHILD_OF));

        var projection = projector.projectFamilyTree(work, graph(List.of()), null, false, 200,
                new RelationshipMentionReader.Snapshot(candidates, 270));

        assertThat(projection.edges()).hasSize(200);
        assertThat(projection.nodes()).hasSize(400).extracting(FamilyTreeProjector.TreeNode::id).doesNotHaveDuplicates();
        assertThat(projection.totalEdgeCount()).isEqualTo(260);
        assertThat(projection.totalNodeCount()).isEqualTo(520);
        assertThat(projection.pendingCandidateCount()).isEqualTo(270);
        assertThat(projection.optionalLinkCount()).isEqualTo(260);
        assertThat(projection.truncated()).isTrue();
        var nodeIds = projection.nodes().stream().map(FamilyTreeProjector.TreeNode::id).toList();
        assertThat(projection.edges()).allSatisfy(edge -> {
            assertThat(nodeIds).contains(edge.subjectNodeId(), edge.objectNodeId());
            assertThat(edge.sourceType()).isEqualTo(FamilyTreeEdgeSourceType.MENTION);
            assertThat(edge.relationshipId()).isNull();
            assertThat(edge.candidateId()).isNotNull();
        });
        assertThat(projection.nodes().stream().filter(node -> node.nodeType() == FamilyTreeNodeType.MENTION))
                .hasSize(200).allSatisfy(node -> {
                    assertThat(node.characterId()).isNull();
                    assertThat(node.id()).isEqualTo("mention:" + node.candidateId());
                    assertThat(node.name()).isEqualTo("동명 인물");
                });
        Collections.reverse(candidates);
        assertThat(projector.projectFamilyTree(work, graph(List.of()), null, false, 200,
                new RelationshipMentionReader.Snapshot(candidates, 270))).isEqualTo(projection);
    }

    @Test
    @DisplayName("미등록 인물 간선은 CHILD_OF만 부모 방향으로 뒤집고 원 후보 ID와 관계 세부값을 유지한다")
    void normalizesMentionParentDirectionAndPreservesCandidateRouting() {
        var child = character(1);
        var parent = mention(1, child, RelationshipKind.CHILD_OF);
        parent.revise(child, null, "동명 인물", RelationshipKind.CHILD_OF, RelationshipSubtype.ADOPTIVE,
                RelationshipTemporalScope.HISTORICAL, "입양 이후", RelationshipAssertion.ASSERTED);
        var spouse = mention(2, child, RelationshipKind.SPOUSE);
        var sibling = mention(3, child, RelationshipKind.SIBLING);

        var projection = projector.projectFamilyTree(work, graph(List.of()), child, true, 100,
                new RelationshipMentionReader.Snapshot(List.of(parent, spouse, sibling), 3));

        assertThat(projection.nodes()).hasSize(4);
        assertThat(projection.edges()).hasSize(3);
        var parentEdge = projection.edges().stream().filter(edge -> edge.candidateId().equals(parent.getId())).findFirst().orElseThrow();
        assertThat(parentEdge.kind()).isEqualTo(RelationshipKind.PARENT_OF);
        assertThat(parentEdge.subjectNodeId()).isEqualTo("mention:" + parent.getId());
        assertThat(parentEdge.objectNodeId()).isEqualTo(child.getId().toString());
        assertThat(parentEdge.subjectCharacterId()).isNull();
        assertThat(parentEdge.objectCharacterId()).isEqualTo(child.getId());
        assertThat(parentEdge.subtype()).isEqualTo(RelationshipSubtype.ADOPTIVE);
        assertThat(parentEdge.temporalScope()).isEqualTo(RelationshipTemporalScope.HISTORICAL);
        assertThat(parentEdge.timeLabel()).isEqualTo("입양 이후");
        assertThat(projection.edges().stream().filter(edge -> edge.kind() != RelationshipKind.PARENT_OF))
                .hasSize(2).allSatisfy(edge -> {
                    assertThat(edge.subjectNodeId()).isEqualTo(child.getId().toString());
                    assertThat(edge.objectNodeId()).isEqualTo("mention:" + edge.candidateId());
                });
    }

    @Test
    @DisplayName("표시 상한 밖 언급 후보의 같은 개수·같은 이름 수정도 revision 변경으로 감지한다")
    void revisionIncludesMentionVersionOutsideDisplayLimit() {
        var first = mention(1, character(1), RelationshipKind.CHILD_OF);
        var hidden = mention(2, character(2), RelationshipKind.CHILD_OF);
        var snapshot = new RelationshipMentionReader.Snapshot(List.of(first, hidden), 2);
        var before = projector.projectFamilyTree(work, graph(List.of()), null, false, 1, snapshot);
        hidden.revise(hidden.getSubjectCharacter(), null, hidden.getObjectMention(), hidden.getKind(), hidden.getSubtype(),
                hidden.getTemporalScope(), hidden.getTimeLabel(), hidden.getAssertion());

        var after = projector.projectFamilyTree(work, graph(List.of()), null, false, 1, snapshot);

        assertThat(after.nodes()).isEqualTo(before.nodes());
        assertThat(after.edges()).isEqualTo(before.edges());
        assertThat(after.optionalLinkCount()).isEqualTo(before.optionalLinkCount());
        assertThat(after.revision()).isNotEqualTo(before.revision());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("표시 상한 밖 확정·언급 간선의 호칭만 달라져도 revision을 변경한다")
    void revisionIncludesEndpointRolesOutsideDisplayLimit(boolean mentionSource) {
        var edges = mentionSource ? List.<CharacterRelationship>of()
                : List.of(edge(1, character(1), character(2)), edge(2, character(3), character(4)));
        var mentions = mentionSource ? List.of(mention(1, character(1), RelationshipKind.CHILD_OF), mention(2, character(2), RelationshipKind.CHILD_OF))
                : List.<RelationshipCandidate>of();
        var snapshot = new RelationshipMentionReader.Snapshot(mentions, mentions.size());
        var graph = graph(edges);
        var before = projector.projectFamilyTree(work, graph, null, false, 1, snapshot);
        String hiddenId = mentionSource ? "mention:" + mentions.getLast().getId() : edges.getLast().getId().toString();
        var after = projector.projectFamilyTree(work, graph, null, false, 1, snapshot,
                Map.of(hiddenId, new FamilyTreeRoleResolver.EndpointRoles(FamilyTreeRole.FATHER, FamilyTreeRole.SON)));

        assertThat(after.nodes()).isEqualTo(before.nodes());
        assertThat(after.edges()).isEqualTo(before.edges());
        assertThat(after.revision()).isNotEqualTo(before.revision());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 201})
    @DisplayName("서비스 직접 호출도 표시 상한을 우회하지 못한다")
    void rejectsInvalidLimitOutsideController(int limit) {
        assertThatThrownBy(() -> projector.projectFamilyTree(work, graph(List.of()), null, false, limit, 0))
                .isInstanceOf(AppException.class);
    }

    private WorkCharacter character(long id) {
        var character = WorkCharacter.create(work, "합성 인물 " + id, null, null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(character, "id", new UUID(0, id));
        return character;
    }

    private CharacterRelationship edge(long id, WorkCharacter subject, WorkCharacter object) {
        var edge = CharacterRelationship.create(work, subject, object, RelationshipKind.PARENT_OF,
                RelationshipSubtype.UNSPECIFIED, RelationshipTemporalScope.CURRENT, null, String.format("%064x", id));
        ReflectionTestUtils.setField(edge, "id", new UUID(0, id));
        return edge;
    }

    private RelationshipCandidate mention(long id, WorkCharacter subject, RelationshipKind kind) {
        var candidate = RelationshipCandidate.create(work, UUID.randomUUID(), String.format("%064x", id),
                subject, null, "동명 인물", kind, RelationshipSubtype.UNSPECIFIED,
                RelationshipTemporalScope.CURRENT, null, RelationshipAssertion.ASSERTED, null);
        ReflectionTestUtils.setField(candidate, "id", new UUID(0, id));
        candidate.markRuleGenerated();
        return candidate;
    }

    private RelationshipGraphReader.Graph graph(List<CharacterRelationship> edges) {
        Map<UUID, RelationshipValidity> validity = new HashMap<>();
        edges.forEach(edge -> validity.put(edge.getId(), RelationshipValidity.VALID));
        return new RelationshipGraphReader.Graph(edges, Map.of(), Map.of(), validity);
    }
}
