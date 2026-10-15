package org.monitoring.catchholebackend.domain.character.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;

@DisplayName("Worker 캐릭터 설정 비교 완료 요청 검증")
class WorkerCharacterFactComparisonCompleteRequestValidationTest {

    @Test
    @DisplayName("기존 단건 JSON에서 새 시점 선택 필드를 생략하면 false로 처리한다")
    void legacySingleWireDefaultsTimeSelectionToFalse() throws Exception {
        var wire = new tools.jackson.databind.ObjectMapper();
        var request = wire.readValue("""
                {"operation":"HISTORY_ONLY","removedSnapshotEntries":[],"temporalScope":"PAST",
                 "comparisonReason":"이력","contextToken":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                """, WorkerCharacterFactComparisonCompleteRequest.class);
        assertThat(request.advanceCurrentNarrativeTime()).isFalse();
    }

    @Test
    @DisplayName("기존 묶음 JSON에서 새 시점 선택 필드를 생략하면 false로 처리한다")
    void legacyBatchWireDefaultsTimeSelectionToFalse() throws Exception {
        var wire = new tools.jackson.databind.ObjectMapper();
        var request = wire.readValue("""
                {"contextToken":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "failures":[],"decisions":[{"candidateRef":"C1","operation":"HISTORY_ONLY",
                 "resolvedCanonicalFactKey":"status.경직","removedSnapshotRefs":[],"dependencyCandidateRefs":[],
                 "temporalScope":"PAST","comparisonReason":"이력"}]}
                """, WorkerCharacterFactComparisonBatchCompleteRequest.class);
        assertThat(request.decisions().getFirst().advanceCurrentNarrativeTime()).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"null,false", "false,false", "true,true"})
    void explicitNullAndBooleanTimeChoiceRemainCompatibleInSingleAndBatchWire(String literal, boolean expected) throws Exception {
        var wire = new tools.jackson.databind.ObjectMapper();
        String decision = """
                {"candidateRef":"C1","operation":"EXCLUDE","resolvedCanonicalFactKey":"status.경직",
                 "removedSnapshotRefs":[],"dependencyCandidateRefs":[],"temporalScope":"PRESENT",
                 "comparisonReason":"현재 관측","advanceCurrentNarrativeTime":%s}
                """.formatted(literal);
        String singleJson = """
                {"operation":"EXCLUDE","removedSnapshotEntries":[],"temporalScope":"PRESENT",
                 "comparisonReason":"현재 관측","contextToken":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "advanceCurrentNarrativeTime":%s}
                """.formatted(literal);
        var single = wire.readValue(singleJson, WorkerCharacterFactComparisonCompleteRequest.class);
        var batch = wire.readValue("{\"contextToken\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"failures\":[],\"decisions\":[" + decision + "]}", WorkerCharacterFactComparisonBatchCompleteRequest.class);
        assertThat(single.advanceCurrentNarrativeTime()).isEqualTo(expected);
        assertThat(batch.decisions().getFirst().advanceCurrentNarrativeTime()).isEqualTo(expected);
    }

    @Test
    @DisplayName("35개 결정·제거 참조·의존 후보 목록은 묶음 완료 요청 검증을 통과한다")
    void acceptsUnboundedBatchDecisionsRemovalsAndDependencies() throws Exception {
        var wire = new tools.jackson.databind.ObjectMapper();
        String refs = java.util.stream.IntStream.rangeClosed(1, 35)
                .mapToObj(index -> "\"Q" + index + "\"").collect(java.util.stream.Collectors.joining(","));
        String deps = java.util.stream.IntStream.rangeClosed(1, 35)
                .mapToObj(index -> "\"C" + index + "\"").collect(java.util.stream.Collectors.joining(","));
        String rows = java.util.stream.IntStream.rangeClosed(1, 35).mapToObj(index -> """
                {"candidateRef":"C%d","operation":"HISTORY_ONLY","resolvedCanonicalFactKey":"status.경직",
                 "removedSnapshotRefs":[],"dependencyCandidateRefs":[],"temporalScope":"PAST","comparisonReason":"이력"}
                """.formatted(index)).collect(java.util.stream.Collectors.joining(","));
        String remove = """
                {"candidateRef":"C36","operation":"REMOVE","resolvedCanonicalFactKey":"status.경직",
                 "removedSnapshotRefs":[%s],"dependencyCandidateRefs":[%s],"temporalScope":"PRESENT","comparisonReason":"회복"}
                """.formatted(refs, deps);
        var request = wire.readValue("{\"contextToken\":\"" + "a".repeat(64)
                + "\",\"failures\":[],\"decisions\":[" + rows + "," + remove + "]}",
                WorkerCharacterFactComparisonBatchCompleteRequest.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
        assertThat(request.decisions()).hasSize(36);
        assertThat(request.decisions().getLast().removedSnapshotRefs()).hasSize(35);
        assertThat(request.decisions().getLast().dependencyCandidateRefs()).hasSize(35);
    }

    @Test
    @DisplayName("제거 대상 목록의 null 항목을 요청 검증 단계에서 거절한다")
    void rejectsNullRemovedSnapshotEntry() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        var request = new WorkerCharacterFactComparisonCompleteRequest(
                CharacterFactOperation.HISTORY_ONLY,
                null,
                null,
                null,
                null,
                java.util.Collections.singletonList(null),
                CharacterFactTemporalScope.PAST,
                "과거 회상으로 현재값에는 반영하지 않음",
                "a".repeat(64),
                null
        );

        assertThat(validator.validate(request))
                .anyMatch(violation -> violation.getPropertyPath().toString()
                        .startsWith("removedSnapshotEntries"));
    }
}
