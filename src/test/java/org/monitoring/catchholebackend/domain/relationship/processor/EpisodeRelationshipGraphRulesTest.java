package org.monitoring.catchholebackend.domain.relationship.processor;

import static org.assertj.core.api.Assertions.*;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.AnalysisStatus;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Axis;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Complete;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Context;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.ContextNode;
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

import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.global.exception.AppException;

import java.util.List;

class EpisodeRelationshipGraphRulesTest {
    private final RelationshipGraphRules rules = new RelationshipGraphRules();
    private final Endpoint a = new Endpoint("아라", "아라"), b = new Endpoint("보라", "보라");
    private final Evidence evidence = new Evidence(0, 11, "아라와 보라는 친구다");

    private Context context(List<ContextRelationship> existing) {
        return new Context(
                1,
                "hash",
                "token",
                AnalysisStatus.PREPARED,
                2,
                List.of(
                        new ContextObservation(
                                "O1",
                                a,
                                b,
                                "배신으로 관계가 달라졌다",
                                List.of(evidence),
                                List.of("N1"),
                                List.of("N2"),
                                2)),
                List.of(new ContextNode("N1", "아라", false), new ContextNode("N2", "보라", false)),
                existing,
                List.of());
    }

    private Decision decision(Operation operation, String ref, Kind kind, Direction direction) {
        return new Decision(
                List.of("O1"),
                operation,
                ref,
                "N1",
                "N2",
                Axis.SOCIAL,
                kind,
                "친구",
                "함께 다닌다",
                direction,
                TemporalScope.PRESENT,
                null);
    }

    @Test
    void validatesUnicodeCodePointOffsetsAndExactSource() {
        String text = "😀 아라와 보라는 친구다";
        Prepare request =
                new Prepare(
                        1,
                        "hash",
                        List.of(
                                new Observation(
                                        "O1",
                                        a,
                                        b,
                                        "친구다",
                                        List.of(new Evidence(2, 13, "아라와 보라는 친구다")))),
                        0);
        assertThatCode(() -> rules.validatePrepare(request, text)).doesNotThrowAnyException();
        Prepare forged =
                new Prepare(
                        1,
                        "hash",
                        List.of(
                                new Observation(
                                        "O1",
                                        a,
                                        b,
                                        "친구다",
                                        List.of(new Evidence(2, 13, "아라와 보라는 적이다")))),
                        0);
        assertThatThrownBy(() -> rules.validatePrepare(forged, text))
                .isInstanceOf(AppException.class);
    }

    @Test
    void oneObservationCanEndFriendshipAndAddHostility() {
        ContextRelationship previous =
                new ContextRelationship(
                        "R1",
                        "N1",
                        "N2",
                        Axis.SOCIAL,
                        Kind.FRIENDSHIP,
                        "친구",
                        "함께 다닌다",
                        Direction.MUTUAL,
                        EdgeStatus.ACTIVE,
                        1,
                        1);
        assertThatCode(
                        () ->
                                rules.validateComplete(
                                        context(List.of(previous)),
                                        new Complete(
                                                1,
                                                "token",
                                                List.of(
                                                        decision(
                                                                Operation.END,
                                                                "R1",
                                                                Kind.FRIENDSHIP,
                                                                Direction.MUTUAL),
                                                        decision(
                                                                Operation.ADD,
                                                                null,
                                                                Kind.HOSTILITY,
                                                                Direction.MUTUAL)))))
                .doesNotThrowAnyException();
    }

    @Test
    void cannotMutateSameSlotTwiceOrContradictHold() {
        Decision add = decision(Operation.ADD, null, Kind.FRIENDSHIP, Direction.MUTUAL);
        Decision hold =
                new Decision(
                        List.of("O1"),
                        Operation.HOLD,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        TemporalScope.UNKNOWN,
                        "인물이 불분명하다");
        assertThatThrownBy(
                        () ->
                                rules.validateComplete(
                                        context(List.of()),
                                        new Complete(1, "token", List.of(add, add))))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(
                        () ->
                                rules.validateComplete(
                                        context(List.of()),
                                        new Complete(1, "token", List.of(add, hold))))
                .isInstanceOf(AppException.class);
    }

    @Test
    void ambiguousIdentityMayOnlyRemainInternal() {
        Context base = context(List.of());
        Context unresolved =
                new Context(
                        1,
                        "hash",
                        "token",
                        AnalysisStatus.PREPARED,
                        2,
                        List.of(
                                new ContextObservation(
                                        "O1",
                                        new Endpoint(null, "나"),
                                        b,
                                        "친구다",
                                        List.of(evidence),
                                        List.of(),
                                        List.of("N2"),
                                        2)),
                        base.nodes(),
                        List.of(),
                        List.of());
        assertThatThrownBy(
                        () ->
                                rules.validateComplete(
                                        unresolved,
                                        new Complete(
                                                1,
                                                "token",
                                                List.of(
                                                        decision(
                                                                Operation.ADD,
                                                                null,
                                                                Kind.FRIENDSHIP,
                                                                Direction.MUTUAL)))))
                .isInstanceOf(AppException.class);
    }

    @Test
    void endedRelationshipReactivatesThroughUpdateNotDuplicateAdd() {
        Context previous =
                context(
                        List.of(
                                new ContextRelationship(
                                        "R1",
                                        "N1",
                                        "N2",
                                        Axis.SOCIAL,
                                        Kind.FRIENDSHIP,
                                        "친구",
                                        "함께 다닌다",
                                        Direction.MUTUAL,
                                        EdgeStatus.ENDED,
                                        1,
                                        1)));
        assertThatCode(
                        () ->
                                rules.validateComplete(
                                        previous,
                                        new Complete(
                                                1,
                                                "token",
                                                List.of(
                                                        decision(
                                                                Operation.UPDATE,
                                                                "R1",
                                                                Kind.FRIENDSHIP,
                                                                Direction.MUTUAL)))))
                .doesNotThrowAnyException();
        for (Operation operation : List.of(Operation.MERGE, Operation.END, Operation.ADD)) {
            assertThatThrownBy(
                            () ->
                                    rules.validateComplete(
                                            previous,
                                            new Complete(
                                                    1,
                                                    "token",
                                                    List.of(
                                                            decision(
                                                                    operation,
                                                                    operation == Operation.ADD
                                                                            ? null
                                                                            : "R1",
                                                                    Kind.FRIENDSHIP,
                                                                    Direction.MUTUAL)))))
                    .isInstanceOf(AppException.class);
        }
    }

    @Test
    void rejectsUncoveredObservationsForgedReferencesAndPastWrites() {
        Context context = context(List.of());
        assertThatThrownBy(
                        () -> rules.validateComplete(context, new Complete(1, "token", List.of())))
                .isInstanceOf(AppException.class);
        Decision invented =
                new Decision(
                        List.of("O1"),
                        Operation.ADD,
                        null,
                        "N1",
                        "N3",
                        Axis.SOCIAL,
                        Kind.FRIENDSHIP,
                        "친구",
                        "친구다",
                        Direction.MUTUAL,
                        TemporalScope.PRESENT,
                        null);
        assertThatThrownBy(
                        () ->
                                rules.validateComplete(
                                        context, new Complete(1, "token", List.of(invented))))
                .isInstanceOf(AppException.class);
        Decision past =
                new Decision(
                        List.of("O1"),
                        Operation.ADD,
                        null,
                        "N1",
                        "N2",
                        Axis.SOCIAL,
                        Kind.FRIENDSHIP,
                        "친구",
                        "친구다",
                        Direction.MUTUAL,
                        TemporalScope.PAST,
                        null);
        assertThatThrownBy(
                        () ->
                                rules.validateComplete(
                                        context, new Complete(1, "token", List.of(past))))
                .isInstanceOf(AppException.class);
    }
}
