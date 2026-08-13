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
        String contextToken
) {

    @Schema(name = "WorkerCharacterCurrentSnapshotEntry")
    public record SnapshotEntry(
            CharacterFactType factType,
            String factKey,
            @Schema(nullable = true)
            String factValue,
            @Schema(nullable = true, implementation = JsonNode.class)
            Object valueJson
    ) {
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
            Object proposedValueJson
    ) {
    }
}
