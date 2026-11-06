package org.monitoring.catchholebackend.domain.relationship.processor;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Axis;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Direction;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.EdgeStatus;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Kind;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphEdge;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphEvent;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphNode;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphCleanupRules.CleanupAbortedException;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphCleanupRules.EdgeRow;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphCleanupRules.EventSlot;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphCleanupRules.Input;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphCleanupRules.NodeAction;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphCleanupRules.NodeActionKind;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphCleanupRules.NodeRow;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphCleanupRules.Phase;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphCleanupRules.Plan;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphCleanupRules.Reason;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipNameRules.ProfileName;
import org.monitoring.catchholebackend.global.hash.Sha256Hasher;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** 운영 사본(바바리안 100화)의 이름 모양을 합성 UUID로 옮긴 1회 정리 계획 규칙. */
@DisplayName("관계도 1회 정리 계획은 이름이 그 프로필로만 해소되는 노드만 합치고 겹친 선을 정정·삭제 억제와 함께 모은다")
class RelationshipGraphCleanupRulesTest {
    private static final int CUTOFF = 100;
    private final Function<String, String> hasher = new Sha256Hasher()::hash;
    private final List<NodeRow> nodes = new ArrayList<>();
    private final List<EdgeRow> edges = new ArrayList<>();
    private final List<EventSlot> events = new ArrayList<>();
    private final Map<String, List<UUID>> confirmed = new LinkedHashMap<>();
    private final List<ProfileName> profiles = new ArrayList<>();

    private UUID profile(String name) {
        UUID id = UUID.randomUUID();
        profiles.add(new ProfileName(id, name));
        return id;
    }

    private void confirm(String name, UUID profile) {
        confirmed.computeIfAbsent(name, x -> new ArrayList<>()).add(profile);
    }

    private NodeRow node(String name, UUID profile, int first) {
        return node(name, profile, first, profile == null ? null : first);
    }

    private NodeRow node(String name, UUID profile, int first, Integer linked) {
        NodeRow row = new NodeRow(UUID.randomUUID(), name, profile, linked, first, null, LocalDateTime.now());
        nodes.add(row);
        return row;
    }

    private EdgeRow edge(NodeRow source, NodeRow target, Kind kind, Direction direction) {
        return edge(source.id(), target.id(), kind, direction, false, null, 1);
    }

    private EdgeRow edge(UUID source, UUID target, Kind kind, Direction direction, boolean suppressed,
            String corrected, long version) {
        EdgeRow row = new EdgeRow(UUID.randomUUID(), source, target, Axis.SOCIAL, kind,
                key(source, target, kind, direction), suppressed, corrected, version, LocalDateTime.now());
        edges.add(row);
        return row;
    }

    private String key(UUID source, UUID target, Kind kind, Direction direction) {
        return hasher.apply(RelationshipGraphRules.canonical(
                source.toString(), target.toString(), Axis.SOCIAL, kind, direction));
    }

    private EventSlot event(EdgeRow edge, UUID analysis, int episodeNo, Direction direction) {
        EventSlot row = new EventSlot(UUID.randomUUID(), edge.id(), analysis, episodeNo, direction);
        events.add(row);
        return row;
    }

    private Input input() {
        return new Input(CUTOFF, List.copyOf(nodes), List.copyOf(edges), List.copyOf(events),
                Map.copyOf(confirmed), List.copyOf(profiles), hasher);
    }

    private Plan plan() {
        return RelationshipGraphCleanupRules.plan(input());
    }

    private static NodeAction action(Plan plan, NodeRow node) {
        return plan.nodes().stream().filter(a -> a.nodeId().equals(node.id())).findFirst().orElseThrow(
                () -> new AssertionError("no action for " + node.name()));
    }

    private static boolean untouched(Plan plan, NodeRow node) {
        return plan.nodes().stream().noneMatch(a -> a.nodeId().equals(node.id()));
    }

    @Test
    @DisplayName("같은 프로필의 연결 노드는 대표 이름의 짧은/긴 형태일 때만 합치고 확정 별칭이어도 대표 이름과 무관하면 둔다")
    void linkedNodeMergesOnlyAsFormOfRepresentativeName() {
        UUID brown = profile("브라운 로트밀러"), misha = profile("미샤 칼스타인");
        confirm("브라운 로트밀러", brown);
        confirm("미샤 칼스타인", misha);
        confirm("테일론 칼스타인", misha);
        NodeRow fullName = node("브라운 로트밀러", brown, 49);
        NodeRow shortName = node("로트밀러", brown, 50, 79);
        NodeRow mishaNode = node("미샤 칼스타인", misha, 49);
        NodeRow taylon = node("테일론 칼스타인", misha, 69);

        Plan plan = plan();

        assertThat(action(plan, shortName)).satisfies(a -> {
            assertThat(a.phase()).isEqualTo(Phase.LINKED);
            assertThat(a.kind()).isEqualTo(NodeActionKind.MERGE_LINKED);
            assertThat(a.targetNodeId()).isEqualTo(fullName.id());
            assertThat(a.profileId()).isEqualTo(brown);
        });
        assertThat(action(plan, taylon)).satisfies(a -> {
            assertThat(a.kind()).isEqualTo(NodeActionKind.SKIPPED);
            assertThat(a.reason()).isEqualTo(Reason.NOT_REPRESENTATIVE_FORM);
        });
        assertThat(untouched(plan, fullName)).isTrue();
        assertThat(untouched(plan, mishaNode)).isTrue();
    }

    @Test
    @DisplayName("다른 ACTIVE 프로필과 정확히 같거나 그 이름에 포함되는 연결 노드는 확정 별칭이 있어도 보류한다")
    void linkedNodeHeldOnActiveProfileCollision() {
        UUID hans = profile("한스"), hodge = profile("한스 호지");
        UUID erwen = profile("에르웬 포르나치 디 테르시아"), daria = profile("다리아 위트엠버 디 테르시아");
        confirm("한스", hans);
        confirm("한스 호지", hans);
        confirm("한스C", hans);
        confirm("에르웬", erwen);
        confirm("테르시아", erwen);
        confirm("다리아 위트엠버 디 테르시아", erwen);
        NodeRow hansNode = node("한스", hans, 6);
        NodeRow hansC = node("한스C", hans, 55);
        NodeRow hodgeNode = node("한스 호지", hans, 85);
        NodeRow erwenNode = node("에르웬", erwen, 9, 11);
        NodeRow tersia = node("테르시아", erwen, 17);
        NodeRow dariaNode = node("다리아 위트엠버 디 테르시아", erwen, 18);

        Plan plan = plan();

        assertThat(action(plan, hansC)).satisfies(a -> {
            assertThat(a.kind()).isEqualTo(NodeActionKind.MERGE_LINKED);
            assertThat(a.targetNodeId()).isEqualTo(hansNode.id());
        });
        assertThat(action(plan, hodgeNode)).satisfies(a -> {
            assertThat(a.kind()).isEqualTo(NodeActionKind.HELD);
            assertThat(a.reason()).isEqualTo(Reason.HELD_COLLISION);
            assertThat(a.resolvedProfileIds()).containsExactlyInAnyOrder(hans, hodge);
        });
        assertThat(action(plan, tersia)).satisfies(a -> {
            assertThat(a.kind()).isEqualTo(NodeActionKind.HELD);
            assertThat(a.resolvedProfileIds()).containsExactlyInAnyOrder(erwen, daria);
        });
        assertThat(action(plan, dariaNode).kind()).isEqualTo(NodeActionKind.HELD);
        // The earliest node is canonical when none carries the representative name.
        assertThat(untouched(plan, erwenNode)).isTrue();
        assertThat(plan.count(NodeActionKind.MERGE_LINKED)).isEqualTo(1);
    }

    @Test
    @DisplayName("보관한 프로필에 연결된 노드는 합치지 않고 보고만 하며 다른 ACTIVE 프로필로 해소되면 그 사실을 남긴다")
    void archivedProfileNodesAreUntouched() {
        UUID archived = UUID.randomUUID(), otherArchived = UUID.randomUUID();
        UUID rainwales = profile("아멜리아 레인웨일즈");
        confirm("아멜리아 레인웨일즈", rainwales);
        NodeRow amelia = node("아멜리아", archived, 81);
        NodeRow full = node("아멜리아 레인웨일즈", archived, 83);
        NodeRow elf = node("엘프눈나", otherArchived, 40);

        Plan plan = plan();

        assertThat(List.of(action(plan, amelia), action(plan, full), action(plan, elf)))
                .allSatisfy(a -> {
                    assertThat(a.kind()).isEqualTo(NodeActionKind.SKIPPED);
                    assertThat(a.phase()).isEqualTo(Phase.LINKED);
                });
        assertThat(action(plan, full)).satisfies(a -> {
            assertThat(a.reason()).isEqualTo(Reason.OTHER_PROFILE);
            assertThat(a.resolvedProfileIds()).containsExactly(rainwales);
        });
        assertThat(action(plan, elf).reason()).isEqualTo(Reason.ARCHIVED_PROFILE);
        assertThat(plan.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("대표 노드는 대표 이름과 같은 노드를 먼저 고르고 없으면 가장 먼저 나온(동률이면 먼저 연결된) 노드를 고른다")
    void canonicalPrefersRepresentativeNameThenEarliest() {
        UUID ara = profile("아라 린");
        confirm("아라 린", ara);
        NodeRow alias = node("아라", ara, 1);
        NodeRow representative = node("아라 린", ara, 5);
        UUID bora = profile("보라 벨 다인");
        confirm("보라 벨 다인", bora);
        NodeRow laterLinked = node("보라 벨", bora, 2, 9);
        NodeRow firstLinked = node("보라", bora, 2, 3);

        Plan plan = plan();

        assertThat(action(plan, alias).targetNodeId()).isEqualTo(representative.id());
        assertThat(untouched(plan, representative)).isTrue();
        assertThat(action(plan, laterLinked).targetNodeId()).isEqualTo(firstLinked.id());
        assertThat(untouched(plan, firstLinked)).isTrue();
    }

    @Test
    @DisplayName("대표 이름과 같은 노드가 없으면 그 프로필로만 해소되는 노드 중에서 대표 노드를 고르고 잘못 먼저 연결된 다른 사람으로 합치지 않는다")
    void fallbackCanonicalMustResolveToTheProfile() {
        UUID misha = profile("미샤 칼스타인"), taylon = profile("테일론 칼스타인");
        confirm("미샤 칼스타인", misha);
        confirm("테일론 칼스타인", taylon);
        // A data error linked the other person first; no node carries the representative name.
        NodeRow wrongFirst = node("테일론 칼스타인", misha, 10);
        NodeRow shortName = node("미샤", misha, 20);
        NodeRow longName = node("미샤 칼스타인님", misha, 30);

        Plan plan = plan();

        assertThat(action(plan, wrongFirst)).satisfies(a -> {
            assertThat(a.kind()).isEqualTo(NodeActionKind.SKIPPED);
            assertThat(a.reason()).isEqualTo(Reason.OTHER_PROFILE);
            assertThat(a.resolvedProfileIds()).containsExactly(taylon);
        });
        assertThat(untouched(plan, shortName)).isTrue();
        assertThat(action(plan, longName)).satisfies(a -> {
            assertThat(a.kind()).isEqualTo(NodeActionKind.MERGE_LINKED);
            assertThat(a.targetNodeId()).isEqualTo(shortName.id());
        });
        assertThat(plan.nodes()).noneMatch(a -> wrongFirst.id().equals(a.targetNodeId()));
    }

    @Test
    @DisplayName("대표 이름과 같은 노드도, 그 프로필로만 해소되는 노드도 없으면 그 묶음은 합치지 않고 노드마다 사유를 남긴다")
    void groupWithoutVerifiedCanonicalStaysSplit() {
        UUID misha = profile("미샤 칼스타인"), taylon = profile("테일론 칼스타인");
        confirm("미샤 칼스타인", misha);
        confirm("테일론 칼스타인", taylon);
        NodeRow wrongFirst = node("테일론 칼스타인", misha, 10);
        NodeRow family = node("칼스타인", misha, 20);

        Plan plan = plan();

        assertThat(action(plan, wrongFirst).reason()).isEqualTo(Reason.OTHER_PROFILE);
        assertThat(action(plan, family)).satisfies(a -> {
            assertThat(a.kind()).isEqualTo(NodeActionKind.HELD);
            assertThat(a.reason()).isEqualTo(Reason.HELD_COLLISION);
        });
        assertThat(plan.count(NodeActionKind.MERGE_LINKED)).isZero();
        assertThat(plan.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("미연결 노드는 다음 반영과 같은 규칙으로 연결·병합·보류·건너뜀을 정한다")
    void unlinkedNodeLinksMergesHoldsOrSkips() {
        UUID bjorn = profile("비요른 얀델"), ragnar = profile("라그나 페프로크");
        UUID hans = profile("한스"), marcom = profile("한스 마르콤");
        UUID elisa = profile("엘리사 베헨크");
        UUID dwalkiA = profile("리올 워브 드왈키"), dwalkiB = profile("드왈키의 첫째 형");
        confirm("비요른 얀델", bjorn);
        confirm("라그나 페프로크", ragnar);
        confirm("한스", hans);
        confirm("한스 마르콤", marcom);
        confirm("엘리사 베헨크", elisa);
        confirm("리올 워브 드왈키", dwalkiA);
        confirm("드왈키의 첫째 형", dwalkiB);
        NodeRow bjornNode = node("비요른 얀델", bjorn, 5);
        node("한스", hans, 6);
        node("한스 마르콤", hans, 81);
        NodeRow shortBjorn = node("비요른", null, 3);
        NodeRow yandel = node("얀델", null, 30);
        NodeRow ragnarNode = node("라그나", null, 85);
        NodeRow hansA = node("한스A", null, 50);
        NodeRow dwalki = node("드왈키", null, 51);
        NodeRow elisaShort = node("엘리사", null, 54);
        NodeRow behenck = node("베헨크", null, 60);
        NodeRow shavinA = node("샤빈", null, 70), shavinB = node("샤빈", null, 71);
        NodeRow late = node("비요른 얀델 공작", null, 101);
        NodeRow stranger = node("무기 상인", null, 23);
        confirm("샤빈 에무어", profile("샤빈 에무어"));

        Plan plan = plan();

        for (NodeRow alias : List.of(shortBjorn, yandel))
            assertThat(action(plan, alias)).satisfies(a -> {
                assertThat(a.phase()).isEqualTo(Phase.UNLINKED);
                assertThat(a.kind()).isEqualTo(NodeActionKind.MERGE_UNLINKED);
                assertThat(a.targetNodeId()).isEqualTo(bjornNode.id());
            });
        assertThat(action(plan, ragnarNode)).satisfies(a -> {
            assertThat(a.kind()).isEqualTo(NodeActionKind.LINK);
            assertThat(a.profileId()).isEqualTo(ragnar);
        });
        // "한스 마르콤" stays linked to 한스 (another profile has that exact name), so 한스 is split.
        assertThat(action(plan, hansA).reason()).isEqualTo(Reason.SPLIT_PROFILE);
        assertThat(action(plan, dwalki)).satisfies(a -> {
            assertThat(a.kind()).isEqualTo(NodeActionKind.HELD);
            assertThat(a.resolvedProfileIds()).containsExactlyInAnyOrder(dwalkiA, dwalkiB);
        });
        // Two aliases of a profile without a node: the first links, the second merges into it.
        assertThat(action(plan, elisaShort).kind()).isEqualTo(NodeActionKind.LINK);
        assertThat(action(plan, behenck)).satisfies(a -> {
            assertThat(a.kind()).isEqualTo(NodeActionKind.MERGE_UNLINKED);
            assertThat(a.targetNodeId()).isEqualTo(elisaShort.id());
        });
        assertThat(List.of(action(plan, shavinA), action(plan, shavinB)))
                .allSatisfy(a -> assertThat(a.reason()).isEqualTo(Reason.DUPLICATE_NAME));
        assertThat(action(plan, late).reason()).isEqualTo(Reason.AFTER_CUTOFF);
        assertThat(untouched(plan, stranger)).isTrue();
    }

    @Test
    @DisplayName("ordered 작품만 ACTIVE 대표 이름을 확정 근거로 쓰고 아니면 대표 이름만으로는 연결하지 않는다")
    void orderedWorkUsesRepresentativeNamesAsAnchors() {
        UUID librarian = profile("라그나 리타니옐 페프로크(도서관 사서)");
        NodeRow ragnar = node("라그나", null, 85);

        Input guardOnly = input();
        Plan unordered = RelationshipGraphCleanupRules.plan(new Input(CUTOFF, guardOnly.nodes(), List.of(), List.of(),
                RelationshipGraphCleanupRules.anchors(confirmed, profiles, false), profiles, hasher));
        Plan ordered = RelationshipGraphCleanupRules.plan(new Input(CUTOFF, guardOnly.nodes(), List.of(), List.of(),
                RelationshipGraphCleanupRules.anchors(confirmed, profiles, true), profiles, hasher));

        assertThat(untouched(unordered, ragnar)).isTrue();
        assertThat(action(ordered, ragnar)).satisfies(a -> {
            assertThat(a.kind()).isEqualTo(NodeActionKind.LINK);
            assertThat(a.profileId()).isEqualTo(librarian);
        });
    }

    @Test
    @DisplayName("옮기는 선은 끝점을 해소한 노드로 바꾸고 저장 key에서 복원한 방향으로 key를 다시 계산한다")
    void edgeMoveRecomputesKeyAndRecoversDirectionFromStoredKey() {
        UUID bjorn = profile("비요른 얀델");
        confirm("비요른 얀델", bjorn);
        NodeRow profileNode = node("비요른 얀델", bjorn, 5);
        NodeRow alias = node("비요른", null, 1);
        NodeRow partner = node("보라", null, 1);
        EdgeRow directed = edge(alias, partner, Kind.COOPERATION, Direction.DIRECTED);
        event(directed, UUID.randomUUID(), 1, Direction.DIRECTED);
        // A reversed undirected edge without events: its direction comes from the stored key alone.
        EdgeRow eventless = edge(partner, alias, Kind.FRIENDSHIP, Direction.UNDIRECTED);

        Plan plan = plan();

        assertThat(plan.moves()).extracting(RelationshipGraphCleanupRules.EdgeMove::edgeId)
                .containsExactlyInAnyOrder(directed.id(), eventless.id());
        assertThat(plan.moves()).filteredOn(m -> m.edgeId().equals(directed.id())).singleElement()
                .satisfies(m -> {
                    assertThat(m.sourceNodeId()).isEqualTo(profileNode.id());
                    assertThat(m.targetNodeId()).isEqualTo(partner.id());
                    assertThat(m.canonicalKey())
                            .isEqualTo(key(profileNode.id(), partner.id(), Kind.COOPERATION, Direction.DIRECTED));
                });
        assertThat(plan.moves()).filteredOn(m -> m.edgeId().equals(eventless.id())).singleElement()
                .satisfies(m -> {
                    assertThat(m.sourceNodeId()).isEqualTo(partner.id());
                    assertThat(m.targetNodeId()).isEqualTo(profileNode.id());
                    assertThat(m.canonicalKey()).isEqualTo(
                            key(profileNode.id(), partner.id(), Kind.FRIENDSHIP, Direction.UNDIRECTED));
                });
        assertThat(plan.merges()).isEmpty();
    }

    @Test
    @DisplayName("저장 key가 어느 방향으로도 맞지 않거나 event 방향과 다르면 작품 전체를 멈춘다")
    void keyMismatchAbortsTheWork() {
        NodeRow a = node("아라", null, 1), b = node("보라", null, 1);
        EdgeRow edge = edge(a, b, Kind.COOPERATION, Direction.MUTUAL);
        event(edge, UUID.randomUUID(), 1, Direction.DIRECTED);

        assertThatThrownBy(this::plan).isInstanceOfSatisfying(CleanupAbortedException.class,
                e -> assertThat(e.code()).isEqualTo("KEY_MISMATCH"));

        events.clear();
        edges.clear();
        edges.add(new EdgeRow(UUID.randomUUID(), a.id(), b.id(), Axis.SOCIAL, Kind.COOPERATION, "0".repeat(64), false,
                null, 1, LocalDateTime.now()));
        assertThatThrownBy(this::plan).isInstanceOfSatisfying(CleanupAbortedException.class,
                e -> assertThat(e.code()).isEqualTo("KEY_MISMATCH"));
    }

    @Test
    @DisplayName("끊기거나 순환하는 redirect는 추측하지 않고 작품 전체를 멈춘다")
    void brokenRedirectAbortsTheWork() {
        nodes.add(new NodeRow(UUID.randomUUID(), "아라", null, null, 1, UUID.randomUUID(), null));

        assertThatThrownBy(this::plan).isInstanceOfSatisfying(CleanupAbortedException.class,
                e -> assertThat(e.code()).isEqualTo("REDIRECT_BROKEN"));
    }

    @Test
    @DisplayName("이미 대표 끝점에 있는 선이 같은 slot의 생존 선이 되고 삭제 억제·정정 설명을 이어받는다")
    void collidingSlotsMergeIntoExistingEdgeAndPreserveEdits() {
        UUID bjorn = profile("비요른 얀델");
        confirm("비요른 얀델", bjorn);
        NodeRow profileNode = node("비요른 얀델", bjorn, 5);
        NodeRow alias = node("비요른", null, 1);
        NodeRow partner = node("보라", null, 1);
        EdgeRow kept = edge(profileNode.id(), partner.id(), Kind.COMPANIONSHIP, Direction.MUTUAL, false,
                "사용자가 정정한 동행", 4);
        event(kept, UUID.randomUUID(), 7, Direction.MUTUAL);
        EdgeRow deleted = edge(alias.id(), partner.id(), Kind.COMPANIONSHIP, Direction.MUTUAL, true, null, 9);
        event(deleted, UUID.randomUUID(), 1, Direction.MUTUAL);
        // A second colliding pair where both edges carry different corrections.
        EdgeRow corrected = edge(profileNode.id(), partner.id(), Kind.FRIENDSHIP, Direction.MUTUAL, false,
                "생존 선 정정", 2);
        EdgeRow otherCorrected = edge(alias.id(), partner.id(), Kind.FRIENDSHIP, Direction.MUTUAL, false,
                "다른 정정", 3);

        Plan plan = plan();

        assertThat(plan.moves()).isEmpty();
        assertThat(plan.groups()).filteredOn(g -> g.survivorId().equals(kept.id())).singleElement()
                .satisfies(g -> {
                    assertThat(g.mergedEdgeIds()).containsExactly(deleted.id());
                    assertThat(g.moved()).isFalse();
                    assertThat(g.suppressed()).isTrue();
                    assertThat(g.corrected()).isTrue();
                    assertThat(g.correctionConflict()).isFalse();
                });
        assertThat(plan.groups()).filteredOn(g -> g.survivorId().equals(corrected.id())).singleElement()
                .satisfies(g -> assertThat(g.correctionConflict()).isTrue());
        assertThat(plan.warnings()).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo("CORRECTION_CONFLICT");
            assertThat(w.survivorId()).isEqualTo(corrected.id());
            assertThat(w.edgeId()).isEqualTo(otherCorrected.id());
        });
    }

    @Test
    @DisplayName("생존 선은 삭제 억제를 OR로, 정정은 자기 것을 우선해 이어받고 version은 둘보다 크게 올린다")
    void survivorAbsorbsEditsAndPassesBothVersions() {
        UUID work = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        RelationshipGraphEdge survivor = RelationshipGraphEdge.create(work, a, b, Axis.SOCIAL, Kind.COOPERATION, "k1");
        survivor.changed();
        RelationshipGraphEdge other = RelationshipGraphEdge.create(work, a, b, Axis.SOCIAL, Kind.COOPERATION, "k2");
        other.correct("사용자 정정");
        other.suppress();
        other.changed();

        survivor.absorb(other);

        assertThat(survivor.isSuppressed()).isTrue();
        assertThat(survivor.getCorrectedSummary()).isEqualTo("사용자 정정");
        assertThat(survivor.getVersion()).isEqualTo(4);
        survivor.correct("생존 선 정정");
        survivor.absorb(other);
        assertThat(survivor.getCorrectedSummary()).isEqualTo("생존 선 정정");
    }

    @Test
    @DisplayName("같은 분석의 event는 근거를 생존 선 event에 합치고 다른 event는 옮기며 끝점 순서가 반대면 이름을 맞바꾼다")
    void sameAnalysisEventsMergeEvidenceOthersMove() {
        UUID bjorn = profile("비요른 얀델");
        confirm("비요른 얀델", bjorn);
        NodeRow profileNode = node("비요른 얀델", bjorn, 5);
        NodeRow alias = node("비요른", null, 1);
        NodeRow partner = node("보라", null, 1);
        UUID shared = UUID.randomUUID(), earlier = UUID.randomUUID();
        EdgeRow kept = edge(profileNode, partner, Kind.COMPANIONSHIP, Direction.MUTUAL);
        EventSlot keptEvent = event(kept, shared, 5, Direction.MUTUAL);
        // Recorded partner→alias, the reverse of the survivor's profile→partner orientation.
        EdgeRow reversed = edge(partner, alias, Kind.COMPANIONSHIP, Direction.MUTUAL);
        EventSlot duplicate = event(reversed, shared, 5, Direction.MUTUAL);
        event(reversed, earlier, 3, Direction.MUTUAL);

        Plan plan = plan();

        assertThat(plan.evidenceMerges()).singleElement().satisfies(m -> {
            assertThat(m.eventId()).isEqualTo(duplicate.id());
            assertThat(m.intoEventId()).isEqualTo(keptEvent.id());
        });
        assertThat(plan.merges()).singleElement().satisfies(m -> {
            assertThat(m.edgeId()).isEqualTo(reversed.id());
            assertThat(m.survivorId()).isEqualTo(kept.id());
            assertThat(m.swapNames()).isTrue();
            assertThat(m.movedEvents()).isEqualTo(1);
        });
        assertThat(plan.movedEvents()).isEqualTo(1);
        assertThat(plan.swappedEvents()).isEqualTo(1);
    }

    @Test
    @DisplayName("근거 합치기는 같은 인용을 다시 넣지 않고 event의 설명·방향은 그대로 둔다")
    void appendEvidenceSkipsIdenticalQuotes() {
        ObjectMapper json = new ObjectMapper();
        JsonNode first = json.createArrayNode().add(json.createObjectNode().put("startOffset", 1).put("endOffset", 4));
        JsonNode other = json.createArrayNode()
                .add(json.createObjectNode().put("startOffset", 1).put("endOffset", 4))
                .add(json.createObjectNode().put("startOffset", 9).put("endOffset", 12));
        RelationshipGraphEvent event = RelationshipGraphEvent.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, "아라", "보라", "동행", "함께 간다", Direction.MUTUAL,
                EdgeStatus.ACTIVE, first);

        event.appendEvidence(other);
        event.appendEvidence(null);

        assertThat(event.getEvidence()).hasSize(2);
        assertThat(event.getEvidence().get(1).path("startOffset").asInt()).isEqualTo(9);
        assertThat(event.getSummary()).isEqualTo("함께 간다");
        assertThat(first).hasSize(1);
    }

    @Test
    @DisplayName("대표 끝점 선이 없으면 별칭 선 중 첫 event가 이른 선이 생존 선이 되어 옮겨진다")
    void twoAliasEdgesToSameNewSlotPickOneSurvivor() {
        UUID bjorn = profile("비요른 얀델");
        confirm("비요른 얀델", bjorn);
        NodeRow profileNode = node("비요른 얀델", bjorn, 5);
        NodeRow shortName = node("비요른", null, 3), family = node("얀델", null, 30);
        NodeRow partner = node("보라", null, 1);
        EdgeRow late = edge(family, partner, Kind.COOPERATION, Direction.MUTUAL);
        event(late, UUID.randomUUID(), 30, Direction.MUTUAL);
        EdgeRow early = edge(shortName, partner, Kind.COOPERATION, Direction.MUTUAL);
        event(early, UUID.randomUUID(), 3, Direction.MUTUAL);

        Plan plan = plan();

        assertThat(plan.moves()).singleElement().satisfies(m -> {
            assertThat(m.edgeId()).isEqualTo(early.id());
            assertThat(m.sourceNodeId()).isEqualTo(profileNode.id());
        });
        assertThat(plan.merges()).singleElement().satisfies(m -> {
            assertThat(m.edgeId()).isEqualTo(late.id());
            assertThat(m.survivorId()).isEqualTo(early.id());
            assertThat(m.swapNames()).isFalse();
        });
        assertThat(plan.groups()).singleElement().satisfies(g -> assertThat(g.moved()).isTrue());
    }

    @Test
    @DisplayName("합친 뒤 자기 자신을 잇게 되는 선은 event와 함께 지운다")
    void selfLoopEdgeIsDeletedWithEvents() {
        UUID bjorn = profile("비요른 얀델");
        confirm("비요른 얀델", bjorn);
        NodeRow profileNode = node("비요른 얀델", bjorn, 5);
        NodeRow alias = node("비요른", null, 1);
        EdgeRow loop = edge(alias, profileNode, Kind.COMPANIONSHIP, Direction.MUTUAL);
        event(loop, UUID.randomUUID(), 5, Direction.MUTUAL);
        event(loop, UUID.randomUUID(), 6, Direction.MUTUAL);

        Plan plan = plan();

        assertThat(plan.selfLoops()).singleElement().satisfies(s -> {
            assertThat(s.edgeId()).isEqualTo(loop.id());
            assertThat(s.events()).isEqualTo(2);
        });
        assertThat(plan.moves()).isEmpty();
        assertThat(plan.selfLoopEvents()).isEqualTo(2);
    }

    @Test
    @DisplayName("이미 런타임 병합으로 생긴 redirect의 선도 같은 방식으로 대표 노드로 옮긴다")
    void existingRedirectEdgesAreMoved() {
        UUID bjorn = profile("비요른 얀델");
        NodeRow profileNode = node("비요른 얀델", bjorn, 5);
        NodeRow redirected = new NodeRow(UUID.randomUUID(), "비요른", null, null, 1, profileNode.id(), null);
        nodes.add(redirected);
        NodeRow partner = node("보라", null, 1);
        EdgeRow edge = edge(redirected, partner, Kind.COOPERATION, Direction.MUTUAL);

        Plan plan = plan();

        assertThat(plan.nodes()).isEmpty();
        assertThat(plan.moves()).singleElement().satisfies(m -> {
            assertThat(m.edgeId()).isEqualTo(edge.id());
            assertThat(m.sourceNodeId()).isEqualTo(profileNode.id());
        });
    }

    @Test
    @DisplayName("정리할 것이 없으면 빈 계획이고 계획을 적용한 상태를 다시 계획해도 비어 있으며 사후 검증을 통과한다")
    void planIsEmptyWhenNothingToDoAndIdempotent() {
        UUID bjorn = profile("비요른 얀델"), brown = profile("브라운 로트밀러");
        confirm("비요른 얀델", bjorn);
        confirm("브라운 로트밀러", brown);
        NodeRow profileNode = node("비요른 얀델", bjorn, 5);
        NodeRow alias = node("비요른", null, 1);
        NodeRow brownNode = node("브라운 로트밀러", brown, 49), rottmiller = node("로트밀러", brown, 50);
        NodeRow partner = node("보라", null, 1);
        EdgeRow kept = edge(profileNode, partner, Kind.COOPERATION, Direction.MUTUAL);
        event(kept, UUID.randomUUID(), 6, Direction.MUTUAL);
        EdgeRow aliasEdge = edge(partner, alias, Kind.COOPERATION, Direction.MUTUAL);
        event(aliasEdge, UUID.randomUUID(), 2, Direction.MUTUAL);
        EdgeRow brownEdge = edge(rottmiller, partner, Kind.HOSTILITY, Direction.DIRECTED);
        event(brownEdge, UUID.randomUUID(), 50, Direction.DIRECTED);
        edge(alias, profileNode, Kind.ACQUAINTANCE, Direction.UNDIRECTED);

        Plan plan = plan();
        assertThat(plan.isEmpty()).isFalse();
        assertThat(RelationshipGraphCleanupRules.verify(nodes, edges, events, hasher)).isEmpty();
        applyInMemory(plan);

        assertThat(RelationshipGraphCleanupRules.verify(nodes, edges, events, hasher)).isEmpty();
        Plan again = plan();
        assertThat(again.isEmpty()).isTrue();
        assertThat(again.groups()).isEmpty();
        assertThat(brownNode.characterId()).isEqualTo(brown);
    }

    /** The same state changes the service applies, on the in-memory rows. */
    private void applyInMemory(Plan plan) {
        Map<UUID, NodeRow> byId = new HashMap<>();
        nodes.forEach(n -> byId.put(n.id(), n));
        for (NodeAction action : plan.nodes()) {
            NodeRow n = byId.get(action.nodeId());
            NodeRow changed = switch (action.kind()) {
                case LINK -> new NodeRow(n.id(), n.name(), action.profileId(), CUTOFF, n.firstEpisodeNo(), null,
                        n.createdAt());
                case MERGE_UNLINKED, MERGE_LINKED -> new NodeRow(n.id(), n.name(), null, null, n.firstEpisodeNo(),
                        action.targetNodeId(), n.createdAt());
                default -> n;
            };
            nodes.set(nodes.indexOf(n), changed);
        }
        Map<UUID, EdgeRow> edgeById = new HashMap<>();
        edges.forEach(e -> edgeById.put(e.id(), e));
        var evidence = plan.evidenceMerges().stream()
                .map(RelationshipGraphCleanupRules.EvidenceMerge::eventId).toList();
        events.removeIf(e -> evidence.contains(e.id()));
        for (var merge : plan.merges()) {
            events.replaceAll(e -> e.edgeId().equals(merge.edgeId())
                    ? new EventSlot(e.id(), merge.survivorId(), e.analysisId(), e.episodeNo(), e.direction()) : e);
            edges.remove(edgeById.get(merge.edgeId()));
        }
        for (var loop : plan.selfLoops()) {
            events.removeIf(e -> e.edgeId().equals(loop.edgeId()));
            edges.remove(edgeById.get(loop.edgeId()));
        }
        for (var move : plan.moves()) {
            EdgeRow e = edgeById.get(move.edgeId());
            edges.set(edges.indexOf(e), new EdgeRow(e.id(), move.sourceNodeId(), move.targetNodeId(), e.axis(),
                    e.kind(), move.canonicalKey(), e.suppressed(), e.correctedSummary(), e.version(), e.createdAt()));
        }
    }

    @Test
    @DisplayName("사후 검증은 병합 노드의 연결·병합 노드 끝점·자기 연결·key 불일치·slot 중복을 모두 센다")
    void verificationCountsEveryViolation() {
        NodeRow a = node("아라", null, 1), b = node("보라", null, 1);
        NodeRow merged = new NodeRow(UUID.randomUUID(), "다라", UUID.randomUUID(), 1, 1, a.id(), null);
        nodes.add(merged);
        edge(merged, b, Kind.COOPERATION, Direction.MUTUAL);
        edges.add(new EdgeRow(UUID.randomUUID(), a.id(), a.id(), Axis.SOCIAL, Kind.COOPERATION, key(a.id(), a.id(),
                Kind.COOPERATION, Direction.MUTUAL), false, null, 1, null));
        EdgeRow first = edge(a, b, Kind.HOSTILITY, Direction.MUTUAL);
        edges.add(new EdgeRow(UUID.randomUUID(), b.id(), a.id(), Axis.SOCIAL, Kind.HOSTILITY, first.canonicalKey(),
                false, null, 1, null));
        edges.add(new EdgeRow(UUID.randomUUID(), a.id(), b.id(), Axis.SOCIAL, Kind.ACQUAINTANCE, "f".repeat(64),
                false, null, 1, null));

        assertThat(RelationshipGraphCleanupRules.verify(nodes, edges, events, hasher)).containsExactlyInAnyOrderEntriesOf(
                Map.of("MERGED_NODE_LINKED", 1, "EDGE_ON_MERGED_NODE", 1, "SELF_LOOP", 1, "KEY_MISMATCH", 1,
                        "DUPLICATE_SLOT", 1));
    }

    @Test
    @DisplayName("같은 프로필에 연결된 노드를 합치면 프로필 연결은 대표 노드에만 남고 병합 노드는 미연결 redirect가 된다")
    void consolidateIntoUnlinksAndRedirects() {
        UUID work = UUID.randomUUID(), profile = UUID.randomUUID(), canonical = UUID.randomUUID();
        RelationshipGraphNode linked = RelationshipGraphNode.create(UUID.randomUUID(), work, profile, "로트밀러", 50);
        RelationshipGraphNode unlinked = RelationshipGraphNode.create(UUID.randomUUID(), work, null, "비요른", 3);

        assertThat(linked.consolidateInto(canonical, 100)).isTrue();
        assertThat(linked.getCharacterId()).isNull();
        assertThat(linked.getCharacterLinkedEpisodeNo()).isNull();
        assertThat(linked.getMergedIntoNodeId()).isEqualTo(canonical);
        assertThat(linked.getMergedAtEpisodeNo()).isEqualTo(100);
        assertThat(linked.getName()).isEqualTo("로트밀러");
        assertThat(linked.getFirstEpisodeNo()).isEqualTo(50);
        assertThat(linked.consolidateInto(canonical, 100)).isFalse();
        assertThat(unlinked.consolidateInto(canonical, 100)).isFalse();
        assertThatThrownBy(() -> linked.consolidateInto(linked.getId(), 100))
                .isInstanceOf(IllegalStateException.class);
    }
}
