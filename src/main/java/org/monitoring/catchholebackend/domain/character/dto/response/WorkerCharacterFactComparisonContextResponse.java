package org.monitoring.catchholebackend.domain.character.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;

public record WorkerCharacterFactComparisonContextResponse(
        WorkerCharacterFactComparisonCandidatePayload candidate,
        List<SnapshotEntry> snapshotEntries,
        List<PriorCandidate> priorCandidates,
        String contextToken,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        List<WorkerCharacterProtectedSlotPayload> protectedSlots,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        WorkerCharacterAdvisoryCurrentContextPayload advisoryCurrentContext
) {
    public WorkerCharacterFactComparisonContextResponse(WorkerCharacterFactComparisonCandidatePayload candidate,
            List<SnapshotEntry> snapshotEntries, List<PriorCandidate> priorCandidates, String contextToken,
            List<WorkerCharacterProtectedSlotPayload> protectedSlots) {
        this(candidate, snapshotEntries, priorCandidates, contextToken, protectedSlots, null);
    }
    public WorkerCharacterFactComparisonContextResponse(WorkerCharacterFactComparisonCandidatePayload candidate,
            List<SnapshotEntry> snapshotEntries, List<PriorCandidate> priorCandidates, String contextToken) {
        this(candidate, snapshotEntries, priorCandidates, contextToken, List.of());
    }

    @Schema(name = "WorkerCharacterCurrentSnapshotEntry")
    public record SnapshotEntry(
            CharacterFactType factType,
            String factKey,
            @Schema(nullable = true)
            String factValue,
            @Schema(nullable = true, implementation = JsonNode.class)
            Object valueJson,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisProvenancePayload provenance
    ) {
        public SnapshotEntry(CharacterFactType factType, String factKey, String factValue, Object valueJson) {
            this(factType, factKey, factValue, valueJson, null);
        }
    }

    /**
     * 같은 업로드 묶음에서 현재 후보보다 앞서 등장한 동일 canonical slot 후보다.
     * 아직 사용자가 확정하지 않은 이력이므로 current snapshot과 구분해 전달한다.
     */
    @Schema(name = "WorkerCharacterPriorFactCandidate")
    public record PriorCandidate(
            @Schema(nullable = true)
            Integer sourceEpisodeNo,
            String attributeName,
            @Schema(nullable = true)
            String attributeValue,
            @Schema(nullable = true, implementation = JsonNode.class)
            Object valueJson,
            List<WorkerCharacterFactComparisonCandidatePayload.EvidenceSpan> evidenceSpans,
            CharacterFactComparisonStatus comparisonStatus,
            @Schema(nullable = true)
            CharacterFactOperation suggestedOperation,
            @Schema(nullable = true)
            String proposedFactValue,
            @Schema(nullable = true, implementation = JsonNode.class)
            Object proposedValueJson,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisSourceResponse source
) {
    public PriorCandidate(Integer sourceEpisodeNo, String attributeName, String attributeValue, Object valueJson, List<WorkerCharacterFactComparisonCandidatePayload.EvidenceSpan> evidenceSpans, CharacterFactComparisonStatus comparisonStatus, CharacterFactOperation suggestedOperation, String proposedFactValue, Object proposedValueJson) {
        this(sourceEpisodeNo, attributeName, attributeValue, valueJson, evidenceSpans, comparisonStatus, suggestedOperation, proposedFactValue, proposedValueJson, null);
    }

    }
}
