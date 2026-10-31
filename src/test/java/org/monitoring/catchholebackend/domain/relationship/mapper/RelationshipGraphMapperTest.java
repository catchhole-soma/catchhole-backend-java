package org.monitoring.catchholebackend.domain.relationship.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.AnalysisStatus;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Axis;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Context;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.ContextNode;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.ContextObservation;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.ContextRelationship;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Direction;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.EdgeStatus;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Endpoint;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Evidence;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Kind;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Observation;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Prepare;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

@DisplayName("관계 그래프의 저장 관측 재사용 요청 변환")
class RelationshipGraphMapperTest {
    private final RelationshipGraphMapper mapper = new RelationshipGraphMapper();

    @Test
    @DisplayName("관측 순서·미상 이름·원문 인용을 보존하고 이전 비교 문맥은 복사하지 않는다")
    void preservesVerifiedObservationsWithoutReusingComparisonContext() {
        var unnamed = new Endpoint(null, "그 사람");
        var ara = new Endpoint("아라", "아라 씨");
        var bora = new Endpoint("보라", "보라");
        var firstEvidence =
                List.of(new Evidence(7, 17, "🙂 아라와  보라\n"), new Evidence(31, 35, "그 사람"));
        var secondEvidence = List.of(new Evidence(41, 47, "함께 걷는다"));
        var context =
                new Context(
                        1,
                        "cached-context-hash",
                        "old-comparison-token",
                        AnalysisStatus.INVALIDATED,
                        8,
                        List.of(
                                new ContextObservation(
                                        "O2", unnamed, bora, "이름 미상의 인물이 보라를 돕는다.",
                                        firstEvidence, List.of("N3", "N4"), List.of("N2"), 8),
                                new ContextObservation(
                                        "O1", ara, bora, "둘은 함께 걷는다.", secondEvidence,
                                        List.of("N1"), List.of("N2"), 8)),
                        List.of(new ContextNode("N1", "앞선 비교의 이름", true)),
                        List.of(
                                new ContextRelationship(
                                        "R1", "N1", "N2", Axis.SOCIAL, Kind.FRIENDSHIP, "친구",
                                        "앞선 비교 결과", Direction.MUTUAL, EdgeStatus.ACTIVE, 1, 7)),
                        List.of(heldObservation()));

        var request = mapper.toPrepareCachedObservations("new-job-source-hash", context, 3);

        assertThat(request)
                .isEqualTo(
                        new Prepare(
                                1,
                                "new-job-source-hash",
                                List.of(
                                        new Observation(
                                                "O2", unnamed, bora, "이름 미상의 인물이 보라를 돕는다.",
                                                firstEvidence),
                                        new Observation(
                                                "O1", ara, bora, "둘은 함께 걷는다.", secondEvidence)),
                                3));
        assertThat(mapper.toJson(request).toString())
                .doesNotContain(
                        "old-comparison-token", "앞선 비교의 이름", "앞선 비교 결과", "이전 회차의 보류 관측",
                        "sourceNodeRefs", "targetNodeRefs", "sourceEpisodeNo");
    }

    @Test
    @DisplayName("현재 관측이 비어 있으면 보류 관측을 섞지 않고 거절 건수만 유지한다")
    void keepsEmptyCurrentObservationsSeparateFromHeldObservations() {
        var context =
                new Context(
                        1, "source-hash", "context-token", AnalysisStatus.PREPARED, 8,
                        List.of(), List.of(), List.of(), List.of(heldObservation()));

        var request = mapper.toPrepareCachedObservations("source-hash", context, 5);

        assertThat(request).isEqualTo(new Prepare(1, "source-hash", List.of(), 5));
    }

    private ContextObservation heldObservation() {
        return new ContextObservation(
                "H1", new Endpoint("아라", "아라"), new Endpoint("보라", "보라"),
                "이전 회차의 보류 관측", List.of(new Evidence(0, 5, "보류 인용")),
                List.of("N1"), List.of("N2"), 7);
    }
}
