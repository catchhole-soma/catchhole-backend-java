package org.monitoring.catchholebackend.domain.character.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
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
        String contextToken
) {

    public record SnapshotEntry(
            String snapshotRef,
            CharacterFactSnapshotOrigin origin,
            @Schema(nullable = true) String sourceCandidateRef,
            List<String> dependencyCandidateRefs,
            CharacterFactType factType,
            String factKey,
            @Schema(nullable = true) String factValue,
            @Schema(nullable = true, implementation = JsonNode.class) Object valueJson
    ) {
    }
}
