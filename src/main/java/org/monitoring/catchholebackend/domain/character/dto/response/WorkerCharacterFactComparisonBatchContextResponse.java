package org.monitoring.catchholebackend.domain.character.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactSnapshotOrigin;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;

@Schema(description = "Worker 캐릭터 Fact 묶음 비교 문맥")
public record WorkerCharacterFactComparisonBatchContextResponse(
        UUID comparisonBatchId,
        String characterRef,
        String matchedCharacterName,
        CharacterFactType canonicalFactType,
        long baseSnapshotVersion,
        List<WorkerCharacterFactComparisonBatchPayload.Candidate> candidates,
        List<SnapshotEntry> snapshotEntries,
        String contextToken,
        @JsonInclude(JsonInclude.Include.NON_NULL) String provisionalSubjectKey,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisContextPayload analysisContext
) {

    public WorkerCharacterFactComparisonBatchContextResponse(
            UUID comparisonBatchId, String characterRef, String matchedCharacterName,
            CharacterFactType canonicalFactType, long baseSnapshotVersion,
            List<WorkerCharacterFactComparisonBatchPayload.Candidate> candidates,
            List<SnapshotEntry> snapshotEntries, String contextToken
    ) {
        this(comparisonBatchId, characterRef, matchedCharacterName, canonicalFactType,
                baseSnapshotVersion, candidates, snapshotEntries, contextToken, null, null);
    }

    public record SnapshotEntry(
            String snapshotRef,
            CharacterFactSnapshotOrigin origin,
            @Schema(nullable = true) String sourceCandidateRef,
            List<String> dependencyCandidateRefs,
            CharacterFactType factType,
            String factKey,
            @Schema(nullable = true) String factValue,
            @Schema(nullable = true, implementation = JsonNode.class) Object valueJson,
            @JsonInclude(JsonInclude.Include.NON_NULL) Provenance provenance
    ) {
        public SnapshotEntry(
                String snapshotRef, CharacterFactSnapshotOrigin origin, String sourceCandidateRef,
                List<String> dependencyCandidateRefs, CharacterFactType factType, String factKey,
                String factValue, Object valueJson
        ) {
            this(snapshotRef, origin, sourceCandidateRef, dependencyCandidateRefs, factType, factKey,
                    factValue, valueJson, null);
        }
    }

    public record Provenance(String confirmationStatus, Integer sourceEpisodeNo,
            @JsonInclude(JsonInclude.Include.NON_NULL) String reviewSource) {
        public Provenance(String confirmationStatus, Integer sourceEpisodeNo) {
            this(confirmationStatus, sourceEpisodeNo, null);
        }
    }
}
