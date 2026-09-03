package org.monitoring.catchholebackend.domain.character.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactCanonicalKeyResolution;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;

@Schema(description = "Worker 캐릭터 Fact 비교 묶음")
public record WorkerCharacterFactComparisonBatchPayload(
        UUID comparisonBatchId,
        UUID workId,
        @Schema(nullable = true) UUID sourceEpisodeId,
        String characterRef,
        String matchedCharacterName,
        CharacterFactType canonicalFactType,
        List<Candidate> candidates
) {

    public record Candidate(
            String candidateRef,
            String projectedSnapshotRef,
            @Schema(nullable = true) Integer sourceEpisodeNo,
            String rawFactKey,
            String initialCanonicalFactKey,
            CharacterFactCanonicalKeyResolution canonicalKeyResolution,
            @Schema(nullable = true) String attributeValue,
            SettingValueType valueType,
            @Schema(nullable = true, implementation = JsonNode.class) Object valueJson,
            List<WorkerCharacterFactComparisonCandidatePayload.EvidenceSpan> evidenceSpans,
            @Schema(nullable = true) BigDecimal confidence
    ) {
    }
}
