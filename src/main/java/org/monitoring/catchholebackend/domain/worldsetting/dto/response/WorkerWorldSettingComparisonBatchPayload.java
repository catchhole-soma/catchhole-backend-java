package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSubjectResolutionType;

@Schema(description = "Worker 세계관 설정 비교 묶음")
public record WorkerWorldSettingComparisonBatchPayload(
        UUID comparisonBatchId,
        UUID workId,
        UUID sourceEpisodeId,
        WorldSettingCategory category,
        WorldSettingSubjectResolutionType resolutionType,
        String canonicalSubjectKey,
        String canonicalSubjectName,
        List<UUID> resolvedTargetWorldSettingIds,
        @Schema(nullable = true) String rawScopeName,
        List<Candidate> candidates,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<String> resolvedProvisionalSubjectKeys,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisContextPayload analysisContext
) {
    public WorkerWorldSettingComparisonBatchPayload(UUID comparisonBatchId, UUID workId, UUID sourceEpisodeId,
            WorldSettingCategory category, WorldSettingSubjectResolutionType resolutionType,
            String canonicalSubjectKey, String canonicalSubjectName, List<UUID> resolvedTargetWorldSettingIds,
            String rawScopeName, List<Candidate> candidates) {
        this(comparisonBatchId, workId, sourceEpisodeId, category, resolutionType, canonicalSubjectKey,
                canonicalSubjectName, resolvedTargetWorldSettingIds, rawScopeName, candidates, null, null);
    }

    public record Candidate(
            String candidateRef,
            UUID candidateId,
            String subjectName,
            @Schema(nullable = true) String scopeName,
            String settingName,
            String extractedValue,
            List<WorkerWorldSettingCandidatePayload.EvidenceSpan> evidenceSpans,
            @Schema(nullable = true) BigDecimal extractionConfidence
    ) {
    }
}
