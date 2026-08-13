package org.monitoring.catchholebackend.domain.character.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;

public record WorkerCharacterFactComparisonCandidatePayload(
        UUID candidateId,
        UUID workId,
        @Schema(description = "후보 원문 회차 ID. 레거시 hidden 재비교 Job에서는 null일 수 있습니다.", nullable = true)
        UUID sourceEpisodeId,
        String entityName,
        String attributeName,
        @Schema(nullable = true)
        String attributeValue,
        SettingValueType valueType,
        @Schema(nullable = true, implementation = JsonNode.class)
        Object valueJson,
        List<EvidenceSpan> evidenceSpans,
        @Schema(nullable = true)
        BigDecimal confidence,
        UUID matchedCharacterId,
        String matchedCharacterName,
        CharacterFactType canonicalFactType,
        String canonicalFactKey
) {

    public record EvidenceSpan(
            @Schema(nullable = true)
            String quote,
            @Schema(nullable = true)
            Integer startOffset,
            @Schema(nullable = true)
            Integer endOffset
    ) {
    }
}
