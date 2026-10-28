package org.monitoring.catchholebackend.domain.character.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;

@Schema(description = "Worker 캐릭터 Fact 묶음 비교 완료 요청")
public record WorkerCharacterFactComparisonBatchCompleteRequest(
        @NotBlank
        @Size(min = 64, max = 64)
        @Pattern(regexp = "[0-9a-f]{64}")
        String contextToken,

        @Valid
        @NotNull
        List<@NotNull @Valid Decision> decisions,

        @Valid
        @NotNull
        List<@NotNull @Valid Failure> failures,

        @Schema(nullable = true)
        Map<String, Object> rawComparisonJson
) {

    @Schema(name = "WorkerCharacterFactComparisonBatchDecision")
    public record Decision(
            @NotBlank
            @Pattern(regexp = "C[1-9][0-9]*")
            @Size(max = 20)
            String candidateRef,

            @NotNull
            CharacterFactOperation operation,

            @NotBlank
            @Size(max = 150)
            String resolvedCanonicalFactKey,

            @Pattern(regexp = "[PQ][1-9][0-9]*")
            @Size(max = 20)
            String targetSnapshotRef,

            @NotNull
            List<@Pattern(regexp = "[PQ][1-9][0-9]*") String> removedSnapshotRefs,

            @NotNull
            List<@Pattern(regexp = "C[1-9][0-9]*") String> dependencyCandidateRefs,

            @Schema(nullable = true)
            String proposedFactValue,

            @Schema(nullable = true, implementation = JsonNode.class)
            Object proposedValueJson,

            @NotNull
            CharacterFactTemporalScope temporalScope,

            @NotBlank
            @Size(max = 2000)
            String comparisonReason,

            @Schema(nullable = true)
            Map<String, Object> rawComparisonJson,
            @jakarta.validation.constraints.Positive @Schema(nullable = true, description = "설정집 본문의 효력 시작 회차") Integer effectiveFromEpisodeNo,
            @Schema(defaultValue = "false", description = "후보의 검증된 referenceTime을 대표 시점으로 선택합니다. 누락/null은 false입니다.")
            Boolean advanceCurrentNarrativeTime
    ) {
        public Decision {
            advanceCurrentNarrativeTime = Boolean.TRUE.equals(advanceCurrentNarrativeTime);
        }
        public Decision(String candidateRef, CharacterFactOperation operation, String resolvedCanonicalFactKey, String targetSnapshotRef,
                List<String> removedSnapshotRefs, List<String> dependencyCandidateRefs, String proposedFactValue, Object proposedValueJson,
                CharacterFactTemporalScope temporalScope, String comparisonReason, Map<String, Object> rawComparisonJson) {
            this(candidateRef, operation, resolvedCanonicalFactKey, targetSnapshotRef, removedSnapshotRefs, dependencyCandidateRefs,
                    proposedFactValue, proposedValueJson, temporalScope, comparisonReason, rawComparisonJson, null, false);
        }
        public Decision(String candidateRef, CharacterFactOperation operation, String resolvedCanonicalFactKey, String targetSnapshotRef,
                List<String> removedSnapshotRefs, List<String> dependencyCandidateRefs, String proposedFactValue, Object proposedValueJson,
                CharacterFactTemporalScope temporalScope, String comparisonReason, Map<String,Object> rawComparisonJson, Integer effectiveFromEpisodeNo) {
            this(candidateRef, operation, resolvedCanonicalFactKey, targetSnapshotRef, removedSnapshotRefs, dependencyCandidateRefs,
                    proposedFactValue, proposedValueJson, temporalScope, comparisonReason, rawComparisonJson, effectiveFromEpisodeNo, false);
        }

    }

    @Schema(name = "WorkerCharacterFactComparisonBatchFailure")
    public record Failure(
            @NotBlank
            @Pattern(regexp = "C[1-9][0-9]*")
            @Size(max = 20)
            String candidateRef,

            @NotNull
            AnalysisFailureCode failureCode,

            @NotBlank
            @Size(max = 1000)
            String errorMessage,
            tools.jackson.databind.JsonNode failureDiagnostics
    ) {
        public Failure(String candidateRef, AnalysisFailureCode failureCode, String errorMessage) {
            this(candidateRef, failureCode, errorMessage, null);
        }
    }
}
