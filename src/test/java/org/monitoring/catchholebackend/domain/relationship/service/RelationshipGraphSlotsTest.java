package org.monitoring.catchholebackend.domain.relationship.service;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Axis;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Direction;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.EdgeStatus;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Kind;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphEdge;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphEvent;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphRules;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.hash.Sha256Hasher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@DisplayName("별칭 관계의 읽기 전용 slot 투영")
class RelationshipGraphSlotsTest {
    private static final UUID WORK = id(100), PROFILE = id(1), ALIAS = id(2), OTHER_ALIAS = id(3), PARTNER = id(4);
    private static final UUID FIRST_ANALYSIS = id(200), SECOND_ANALYSIS = id(201), INVALID_ANALYSIS = id(202);
    private static final LocalDateTime EARLY = LocalDateTime.of(2026, 1, 1, 0, 0);
    private final Function<String, String> hash = new Sha256Hasher()::hash;
    private final Function<UUID, UUID> resolve = value ->
            Map.of(ALIAS, PROFILE, OTHER_ALIAS, PROFILE).getOrDefault(value, value);

    @Test
    @DisplayName("이전 별칭의 ACTIVE보다 새 END를 선택하되 과거 조회는 원래 상태를 유지한다")
    void selectsLatestValidStateAcrossMembersAndPreservesHistoricalCutoff() {
        var alias = edge(10, ALIAS, PARTNER, Axis.SOCIAL, Kind.COMPANIONSHIP, Direction.MUTUAL);
        var canonical = edge(11, PROFILE, PARTNER, Axis.SOCIAL, Kind.COMPANIONSHIP, Direction.MUTUAL);
        var active = event(20, alias, FIRST_ANALYSIS, 1, EdgeStatus.ACTIVE, Direction.MUTUAL, "별칭", "상대");
        var ended = event(21, canonical, SECOND_ANALYSIS, 3, EdgeStatus.ENDED, Direction.MUTUAL, "프로필", "상대");
        var invalid = event(22, alias, INVALID_ANALYSIS, 4, EdgeStatus.ACTIVE, Direction.MUTUAL, "별칭", "상대");

        var slot = project(List.of(alias, canonical), List.of(invalid, ended, active)).getFirst();

        assertThat(slot.representative()).isSameAs(canonical);
        assertThat(slot.members()).containsExactly(canonical, alias);
        assertThat(slot.latest(Set.of(FIRST_ANALYSIS, SECOND_ANALYSIS), 4)).isSameAs(ended);
        assertThat(slot.latest(Set.of(FIRST_ANALYSIS, SECOND_ANALYSIS), 1)).isSameAs(active);
        assertThat(slot.knownFrom(Set.of(FIRST_ANALYSIS, SECOND_ANALYSIS), 4)).isEqualTo(1);
        assertThat(slot.latest(Set.of(SECOND_ANALYSIS), 1)).isNull();
        assertThat(slot.knownFrom(Set.of(SECOND_ANALYSIS), 1)).isZero();
        assertThat(slot.events()).containsExactlyInAnyOrder(active, ended, invalid);
        assertThat(alias.getSourceNodeId()).isEqualTo(ALIAS);
        assertThat(active.getStatus()).isEqualTo(EdgeStatus.ACTIVE);
        assertThat(alias.getVersion()).isZero();
    }

    @Test
    @DisplayName("유효 event가 없는 구성원의 삭제와 정정도 유지하고 버전은 모든 구성원을 합산한다")
    void includesSuppressedAndCorrectedMembersWithoutVisibleEvents() {
        var canonical = edge(10, PROFILE, PARTNER, Axis.ATTITUDE, Kind.TRUST, Direction.DIRECTED);
        var alias = edge(11, ALIAS, PARTNER, Axis.ATTITUDE, Kind.TRUST, Direction.DIRECTED);
        ReflectionTestUtils.setField(canonical, "version", 7L);
        alias.correct("작가의 정정");
        alias.suppress();
        var current = event(20, canonical, FIRST_ANALYSIS, 1, EdgeStatus.ACTIVE, Direction.DIRECTED, "프로필", "상대");

        var slot = project(List.of(alias, canonical), List.of(current)).getFirst();

        assertThat(slot.suppressed()).isTrue();
        assertThat(slot.correctedSummary()).isEqualTo("작가의 정정");
        assertThat(slot.version()).isEqualTo(9);
        alias.changed();
        assertThat(project(List.of(canonical, alias), List.of(current)).getFirst().version()).isEqualTo(10);
    }

    @Test
    @DisplayName("대표의 정정을 먼저 사용하고 같은 회차는 대표 event를 먼저 선택한다")
    void keepsSurvivorCorrectionAndSameEpisodeEventBeforeCreationTime() {
        var canonical = edge(10, PROFILE, PARTNER, Axis.SOCIAL, Kind.COMPANIONSHIP, Direction.MUTUAL);
        var alias = edge(11, ALIAS, PARTNER, Axis.SOCIAL, Kind.COMPANIONSHIP, Direction.MUTUAL);
        canonical.correct("대표 정정");
        alias.correct("다른 정정");
        var survivorEvent = event(20, canonical, FIRST_ANALYSIS, 2, EdgeStatus.ENDED, Direction.MUTUAL, "프로필", "상대");
        var aliasEvent = event(21, alias, FIRST_ANALYSIS, 2, EdgeStatus.ACTIVE, Direction.MUTUAL, "별칭", "상대");
        ReflectionTestUtils.setField(aliasEvent, "createdAt", EARLY.plusDays(1));

        var slot = project(List.of(alias, canonical), List.of(aliasEvent, survivorEvent)).getFirst();

        assertThat(slot.correctedSummary()).isEqualTo("대표 정정");
        assertThat(slot.latest(Set.of(FIRST_ANALYSIS), 2)).isSameAs(survivorEvent);
        assertThat(slot.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("대표가 없으면 무효 분석을 포함한 최초 회차와 생성 시각과 ID로 대표를 고정한다")
    void choosesMovedSurvivorFromAllRawEventsThenCreationAndId() {
        var early = edge(10, ALIAS, PARTNER, Axis.SOCIAL, Kind.COMPANIONSHIP, Direction.MUTUAL);
        var later = edge(11, OTHER_ALIAS, PARTNER, Axis.SOCIAL, Kind.COMPANIONSHIP, Direction.MUTUAL);
        ReflectionTestUtils.setField(early, "createdAt", EARLY.plusDays(2));
        var invalid = event(20, early, INVALID_ANALYSIS, 1, EdgeStatus.ACTIVE, Direction.MUTUAL, "별칭", "상대");
        var valid = event(21, later, FIRST_ANALYSIS, 3, EdgeStatus.ACTIVE, Direction.MUTUAL, "다른 별칭", "상대");

        var slot = project(List.of(later, early), List.of(valid, invalid)).getFirst();

        assertThat(slot.representative()).isSameAs(early);
        assertThat(slot.latest(Set.of(FIRST_ANALYSIS), 3)).isSameAs(valid);
        assertThat(slot.knownFrom(Set.of(FIRST_ANALYSIS), 3)).isEqualTo(3);
        assertThat(project(List.of(early, later), List.of()).getFirst().representative()).isSameAs(later);
        ReflectionTestUtils.setField(early, "createdAt", EARLY);
        assertThat(project(List.of(later, early), List.of()).getFirst().representative()).isSameAs(early);
    }

    @Test
    @DisplayName("역방향 대칭 event의 이름을 대표 방향에 맞추되 원본은 수정하지 않는다")
    void alignsSymmetricEventNamesWithRepresentativeWithoutMutation() {
        var canonical = edge(10, PROFILE, PARTNER, Axis.SOCIAL, Kind.COMPANIONSHIP, Direction.MUTUAL);
        var reversed = edge(11, PARTNER, ALIAS, Axis.SOCIAL, Kind.COMPANIONSHIP, Direction.MUTUAL);
        var event = event(20, reversed, FIRST_ANALYSIS, 2, EdgeStatus.ACTIVE, Direction.MUTUAL, "상대", "별칭");

        var slot = project(List.of(reversed, canonical), List.of(event)).getFirst();

        assertThat(slot.source()).isEqualTo(PROFILE);
        assertThat(slot.target()).isEqualTo(PARTNER);
        assertThat(slot.sourceName(event)).isEqualTo("별칭");
        assertThat(slot.targetName(event)).isEqualTo("상대");
        assertThat(event.getSourceName()).isEqualTo("상대");
        assertThat(event.getTargetName()).isEqualTo("별칭");
    }

    @Test
    @DisplayName("방향과 축과 종류가 다른 관계를 합치지 않고 redirect 자기 관계만 제외한다")
    void preservesDistinctSlotsAndSkipsResolvedSelfLoops() {
        var forward = edge(10, PROFILE, PARTNER, Axis.ATTITUDE, Kind.TRUST, Direction.DIRECTED);
        var reverse = edge(11, PARTNER, ALIAS, Axis.ATTITUDE, Kind.TRUST, Direction.DIRECTED);
        var mutual = edge(12, ALIAS, PARTNER, Axis.ATTITUDE, Kind.TRUST, Direction.MUTUAL);
        var otherAxis = edge(13, PROFILE, PARTNER, Axis.SOCIAL, Kind.TRUST, Direction.DIRECTED);
        var otherKind = edge(14, PROFILE, PARTNER, Axis.ATTITUDE, Kind.DISTRUST, Direction.DIRECTED);
        var selfLoop = edge(15, ALIAS, PROFILE, Axis.ATTITUDE, Kind.TRUST, Direction.DIRECTED);

        assertThat(project(List.of(forward, reverse, mutual, otherAxis, otherKind, selfLoop), List.of()))
                .hasSize(5).extracting(RelationshipGraphSlots.Slot::representative)
                .containsExactly(forward, reverse, mutual, otherAxis, otherKind);
    }

    @Test
    @DisplayName("잘못된 저장 key나 event 방향은 추측하지 않고 거절한다")
    void rejectsUnrecoverableKeyAndConflictingEventDirection() {
        var edge = edge(10, PROFILE, PARTNER, Axis.ATTITUDE, Kind.TRUST, Direction.DIRECTED);
        var invalidEvent = event(20, edge, FIRST_ANALYSIS, 1, EdgeStatus.ACTIVE, Direction.MUTUAL, "프로필", "상대");
        assertThatThrownBy(() -> project(List.of(edge), List.of(invalidEvent))).isInstanceOf(AppException.class);
        ReflectionTestUtils.setField(edge, "canonicalKey", "damaged");
        assertThatThrownBy(() -> project(List.of(edge), List.of())).isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("그룹 버전 합산의 오버플로를 작은 버전으로 되돌리지 않는다")
    void rejectsVersionOverflow() {
        var canonical = edge(10, PROFILE, PARTNER, Axis.ATTITUDE, Kind.TRUST, Direction.DIRECTED);
        var alias = edge(11, ALIAS, PARTNER, Axis.ATTITUDE, Kind.TRUST, Direction.DIRECTED);
        ReflectionTestUtils.setField(canonical, "version", Long.MAX_VALUE);
        alias.changed();
        assertThatThrownBy(() -> project(List.of(canonical, alias), List.of()))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("UUID 동률 비교는 부호 있는 자바 순서가 아닌 SQL의 문자열 순서를 따른다")
    void usesLexicalUuidOrderForSurvivorAndEventTies() {
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff0");
        var lowEdge = edge(10, ALIAS, PARTNER, Axis.SOCIAL, Kind.COMPANIONSHIP, Direction.MUTUAL);
        var highEdge = edge(11, OTHER_ALIAS, PARTNER, Axis.SOCIAL, Kind.COMPANIONSHIP, Direction.MUTUAL);
        ReflectionTestUtils.setField(lowEdge, "id", low);
        ReflectionTestUtils.setField(highEdge, "id", high);
        assertThat(project(List.of(highEdge, lowEdge), List.of()).getFirst().representative()).isSameAs(lowEdge);

        var lowEvent = event(20, lowEdge, FIRST_ANALYSIS, 1, EdgeStatus.ACTIVE, Direction.MUTUAL, "별칭", "상대");
        var highEvent = event(21, lowEdge, SECOND_ANALYSIS, 1, EdgeStatus.ENDED, Direction.MUTUAL, "별칭", "상대");
        ReflectionTestUtils.setField(lowEvent, "id", low);
        ReflectionTestUtils.setField(highEvent, "id", high);
        var slot = project(List.of(lowEdge), List.of(highEvent, lowEvent)).getFirst();
        assertThat(slot.latest(Set.of(FIRST_ANALYSIS, SECOND_ANALYSIS), 1)).isSameAs(highEvent);
    }

    private List<RelationshipGraphSlots.Slot> project(List<RelationshipGraphEdge> edges,
            List<RelationshipGraphEvent> events) {
        return RelationshipGraphSlots.project(edges, events, resolve, hash);
    }

    private RelationshipGraphEdge edge(int number, UUID source, UUID target, Axis axis, Kind kind, Direction direction) {
        var edge = RelationshipGraphEdge.create(WORK, source, target, axis, kind,
                hash.apply(RelationshipGraphRules.canonical(source.toString(), target.toString(), axis, kind, direction)));
        ReflectionTestUtils.setField(edge, "id", id(number));
        ReflectionTestUtils.setField(edge, "createdAt", EARLY);
        return edge;
    }

    private RelationshipGraphEvent event(int number, RelationshipGraphEdge edge, UUID analysis, int episode,
            EdgeStatus status, Direction direction, String sourceName, String targetName) {
        var event = RelationshipGraphEvent.create(WORK, analysis, edge.getId(), id(300 + episode), episode,
                sourceName, targetName, "관계", "합성 관계 설명", direction, status,
                JsonNodeFactory.instance.arrayNode());
        ReflectionTestUtils.setField(event, "id", id(number));
        ReflectionTestUtils.setField(event, "createdAt", EARLY);
        return event;
    }

    private static UUID id(int value) { return new UUID(0, value); }
}
