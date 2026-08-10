package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;

@Schema(description = "Worker 세계관 설정 후보 payload")
public record WorkerWorldSettingCandidatePayload(
        UUID candidateId,
        UUID workId,
        UUID sourceEpisodeId,
        WorldSettingCategory category,
        String subjectName,
        @Schema(nullable = true) String scopeName,
        String settingName,
        String extractedValue,
        List<EvidenceSpan> evidenceSpans,
        BigDecimal extractionConfidence
) {

    public record EvidenceSpan(
            String quote,
            Integer startOffset,
            Integer endOffset
    ) {
    }
}
