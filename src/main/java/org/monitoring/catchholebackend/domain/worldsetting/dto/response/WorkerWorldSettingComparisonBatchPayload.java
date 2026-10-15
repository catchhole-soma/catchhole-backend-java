package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSubjectResolutionType;

@Schema(description = "Worker 세계관 설정 비교 묶음")
public record WorkerWorldSettingComparisonBatchPayload(
        UUID comparisonBatchId,
        UUID workId,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) UUID sourceEpisodeId,
        WorldSettingCategory category,
        WorldSettingSubjectResolutionType resolutionType,
        String canonicalSubjectKey,
        String canonicalSubjectName,
        List<UUID> resolvedTargetWorldSettingIds,
        @Schema(nullable = true) String rawScopeName,
        List<Candidate> candidates,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<String> resolvedProvisionalSubjectKeys,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisContextPayload analysisContext,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisSourceResponse source
) {
    public WorkerWorldSettingComparisonBatchPayload(UUID comparisonBatchId, UUID workId, UUID sourceEpisodeId, WorldSettingCategory category, WorldSettingSubjectResolutionType resolutionType, String canonicalSubjectKey, String canonicalSubjectName, List<UUID> resolvedTargetWorldSettingIds, String rawScopeName, List<Candidate> candidates, List<String> resolvedProvisionalSubjectKeys, org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisContextPayload analysisContext) {
        this(comparisonBatchId, workId, sourceEpisodeId, category, resolutionType, canonicalSubjectKey, canonicalSubjectName, resolvedTargetWorldSettingIds, rawScopeName, candidates, resolvedProvisionalSubjectKeys, analysisContext, null);
    }

    public WorkerWorldSettingComparisonBatchPayload(UUID comparisonBatchId, UUID workId, UUID sourceEpisodeId,
            WorldSettingCategory category, WorldSettingSubjectResolutionType resolutionType,
            String canonicalSubjectKey, String canonicalSubjectName, List<UUID> resolvedTargetWorldSettingIds,
            String rawScopeName, List<Candidate> candidates) {
        this(comparisonBatchId, workId, sourceEpisodeId, category, resolutionType, canonicalSubjectKey,
                canonicalSubjectName, resolvedTargetWorldSettingIds, rawScopeName, candidates, null, null, null);
    }

    public record Candidate(
            String candidateRef,
            UUID candidateId,
            String subjectName,
            @Schema(nullable = true) String scopeName,
            String settingName,
            String extractedValue,
            List<WorkerWorldSettingCandidatePayload.EvidenceSpan> evidenceSpans,
            @Schema(nullable = true) BigDecimal extractionConfidence,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisSourceResponse source,
        org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope temporalScope,
        @Schema(nullable = true) Integer effectiveFromEpisodeNo,
        @Schema(nullable = true, description = "보존된 내부 추출 원본과 BOOK 주장 상태")
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Map<String, Object> rawExtractionJson
) {
    public Candidate(String candidateRef, UUID candidateId, String subjectName, String scopeName, String settingName, String extractedValue, List<WorkerWorldSettingCandidatePayload.EvidenceSpan> evidenceSpans, BigDecimal extractionConfidence, org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisSourceResponse source, org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope temporalScope, Integer effectiveFromEpisodeNo) {
        this(candidateRef, candidateId, subjectName, scopeName, settingName, extractedValue, evidenceSpans, extractionConfidence, source, temporalScope, effectiveFromEpisodeNo, null);
    }
    public Candidate(String candidateRef, UUID candidateId, String subjectName, String scopeName, String settingName, String extractedValue, List<WorkerWorldSettingCandidatePayload.EvidenceSpan> evidenceSpans, BigDecimal extractionConfidence, org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisSourceResponse source) {
        this(candidateRef, candidateId, subjectName, scopeName, settingName, extractedValue, evidenceSpans, extractionConfidence, source, source != null && source.sourceKind() == org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.SETTING_BOOK
                ? org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope.UNKNOWN : org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope.PRESENT, null);
    }
    public Candidate(String candidateRef, UUID candidateId, String subjectName, String scopeName, String settingName, String extractedValue, List<WorkerWorldSettingCandidatePayload.EvidenceSpan> evidenceSpans, BigDecimal extractionConfidence) {
        this(candidateRef, candidateId, subjectName, scopeName, settingName, extractedValue, evidenceSpans, extractionConfidence, null, org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope.PRESENT, null);
    }

    }
}
