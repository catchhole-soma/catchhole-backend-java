package org.monitoring.catchholebackend.domain.relationship.processor;

import static org.assertj.core.api.Assertions.*;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.monitoring.catchholebackend.global.exception.AppException;

@DisplayName("관계 부분 완료의 저장 경계 검증")
class RelationshipPartialResultsRulesTest {
    final RelationshipGraphRules rules = new RelationshipGraphRules();
    final String reason = "RELATIONSHIP_ENDPOINTS_UNGROUNDED";

    ContextObservation observation(String ref, String target) {
        return new ContextObservation(ref, new Endpoint("아라", "아라"), new Endpoint(target, target),
                "함께 이동한다", List.of(new Evidence(0, 1, "합성")), List.of("N1"), List.of(target), 3);
    }
    Context context(boolean partial, List<BlockedPair> blocked) {
        return new Context(1, "hash", "token", AnalysisStatus.PREPARED, 3,
                List.of(observation("O1", "N2"), observation("O2", "N2"), observation("O3", "N3")),
                List.of(new ContextNode("N1", "아라", false), new ContextNode("N2", "보라", false),
                        new ContextNode("N3", "다라", false)), List.of(), List.of(), partial, blocked);
    }
    Decision independent() {
        return new Decision(List.of("O3"), Operation.ADD, null, "N1", "N3", Axis.INTERACTION,
                Kind.COOPERATION, "협력", "함께 이동한다", Direction.MUTUAL, TemporalScope.PRESENT, null);
    }
    Complete result(List<String> failedRefs, String code) {
        return new Complete(1, "token", List.of(independent()), List.of(new GroupFailure(failedRefs, code)));
    }

    @Test
    @DisplayName("실패한 인물 쌍과 정상 인물 쌍이 새 관측 전체를 처리한다")
    void completePairFailureAndIndependentSuccessCoverAllNewObservations() {
        assertThatCode(() -> rules.validateComplete(context(true, List.of()), result(List.of("O1", "O2"), reason)))
                .doesNotThrowAnyException();
    }
    @Test
    @DisplayName("실패 인물 쌍을 나누거나 다른 출처를 섞을 수 없다")
    void cannotSplitFailedPairOrClaimOtherPairOrRepeatRef() {
        for (var refs : List.of(List.of("O1"), List.of("O1", "O3"), List.of("O1", "O1", "O2"), List.of("H1"))) {
            assertThatThrownBy(() -> rules.validateComplete(context(true, List.of()), result(refs, reason)))
                    .isInstanceOf(AppException.class);
        }
    }
    @Test
    @DisplayName("권한 및 제공자 장애를 부분 성공으로 우회할 수 없다")
    void cannotTurnAuthorityOrProviderFailureIntoPartialSuccess() {
        for (String code : List.of("RELATIONSHIP_CONTEXT_INVALID", "AI_TOKEN_QUOTA_EXHAUSTED",
                "LLM_PROVIDER_ERROR", "ANALYSIS_JOB_LEASE_CONFLICT", "arbitrary model text")) {
            assertThatThrownBy(() -> rules.validateComplete(context(true, List.of()), result(List.of("O1", "O2"), code)))
                    .isInstanceOf(AppException.class);
        }
    }
    @Test
    @DisplayName("선행 실패는 지정 원인으로 보존하며 보류나 새 실패로 위장할 수 없다")
    void predecessorRequiresTypedFailureAndCannotBeDisguisedAsHoldOrRetryFailure() {
        var context = context(true, List.of(new BlockedPair("N2", "N1", 1)));
        assertThatCode(() -> rules.validateComplete(context, result(List.of("O1", "O2"), RelationshipGraphRules.PREDECESSOR_INCOMPLETE)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> rules.validateComplete(context, result(List.of("O1", "O2"), reason)))
                .isInstanceOf(AppException.class);
        Decision hold = new Decision(List.of("O1", "O2"), Operation.HOLD, null, null, null, null, null,
                null, null, null, TemporalScope.UNKNOWN, "판단 보류");
        assertThatThrownBy(() -> rules.validateComplete(context, new Complete(1, "token", List.of(hold, independent()))))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> rules.validateComplete(context(true, List.of()),
                result(List.of("O1", "O2"), RelationshipGraphRules.PREDECESSOR_INCOMPLETE)))
                .isInstanceOf(AppException.class);
    }
    @Test
    @DisplayName("실패 관측을 정상 결정에서 재사용할 수 없다")
    void failedObservationsCannotAlsoProduceSuccessfulRelations() {
        var context = context(true, List.of());
        Decision hold = new Decision(List.of("O1", "O2"), Operation.HOLD, null, null, null, null, null,
                null, null, null, TemporalScope.UNKNOWN, "판단 보류");
        assertThatThrownBy(() -> rules.validateComplete(context, new Complete(1, "token", List.of(hold, independent()),
                List.of(new GroupFailure(List.of("O1", "O2"), reason)))))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("다른 인물 쌍의 보류와 제외가 실패 인물 쌍의 과거 보류를 소비할 수 없다")
    void unrelatedDispositionCannotConsumeHistoricalEvidenceOfFailedPair() {
        var base = context(true, List.of());
        Context context = new Context(1, "hash", "token", AnalysisStatus.PREPARED, 3,
                base.observations(), base.nodes(), List.of(), List.of(observation("H1", "N2")), true, List.of());
        for (Operation operation : List.of(Operation.HOLD, Operation.EXCLUDE)) {
            Decision crossed = new Decision(List.of("O3", "H1"), operation, null, null, null, null, null,
                    null, null, null, TemporalScope.UNKNOWN, "합성 판단");
            assertThatThrownBy(() -> rules.validateComplete(context, new Complete(1, "token", List.of(crossed),
                    List.of(new GroupFailure(List.of("O1", "O2"), reason)))))
                    .isInstanceOf(AppException.class);
        }
    }

    @Test
    @DisplayName("인물이 미확정인 새 관측은 독립 보류로만 보존한다")
    void unresolvedNewObservationIsOnlyAnIndependentHold() {
        var base = context(true, List.of());
        var unresolved = new ContextObservation("O1", new Endpoint("아라", "아라"), new Endpoint(null, "그녀"),
                "이름 미상", List.of(new Evidence(0, 1, "합성")), List.of("N1"), List.of(), 3);
        var context = new Context(1, "hash", "token", AnalysisStatus.PREPARED, 3, List.of(unresolved),
                base.nodes(), List.of(), List.of(), true, List.of());
        var hold = new Decision(List.of("O1"), Operation.HOLD, null, null, null, null, null,
                null, null, null, TemporalScope.UNKNOWN, "인물 연결 미확정");
        assertThatCode(() -> rules.validateComplete(context, new Complete(1, "token", List.of(hold))))
                .doesNotThrowAnyException();
        var excluded = new Decision(List.of("O1"), Operation.EXCLUDE, null, null, null, null, null,
                null, null, null, TemporalScope.UNKNOWN, "미상 인물 제외");
        assertThatThrownBy(() -> rules.validateComplete(context, new Complete(1, "token", List.of(excluded))))
                .isInstanceOf(AppException.class);
    }
    @Test
    @DisplayName("구형 고정 문맥은 부분 결과를 거부하고 새 계약 필드를 생략한다")
    void oldFrozenContextRejectsPartialPayloadAndOmitsOptInFields() throws Exception {
        var context = context(false, List.of());
        assertThatThrownBy(() -> rules.validateComplete(context, result(List.of("O1", "O2"), reason)))
                .isInstanceOf(AppException.class);
        var wire = new ObjectMapper().valueToTree(context);
        assertThat(wire.has("supportsPartialResults")).isFalse();
        assertThat(wire.has("blockedPairs")).isFalse();
        assertThat(wire.has("failures")).isFalse();
    }
    @Test
    @DisplayName("선행 실패는 알려진 서로 다른 인물과 과거 회차만 참조한다")
    void malformedPredecessorCannotInventUnknownNodesOrCurrentEpisodeFailure() {
        for (var pair : List.of(new BlockedPair("N1", "N404", 1), new BlockedPair("N1", "N2", 3),
                new BlockedPair("N1", "N1", 1))) {
            assertThatThrownBy(() -> rules.validateComplete(context(true, List.of(pair)), result(List.of("O1", "O2"), reason)))
                    .isInstanceOf(AppException.class);
        }
    }
}
