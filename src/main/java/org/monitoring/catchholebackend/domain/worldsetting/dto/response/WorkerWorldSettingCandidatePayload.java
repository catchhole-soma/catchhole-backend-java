package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;

@Schema(description = "Worker 세계관 설정 후보 payload")
public record WorkerWorldSettingCandidatePayload(
        UUID candidateId,
        UUID workId,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) UUID sourceEpisodeId,
        WorldSettingCategory category,
        String subjectName,
        @Schema(nullable = true) String scopeName,
        String settingName,
        String extractedValue,
        List<EvidenceSpan> evidenceSpans,
        BigDecimal extractionConfidence,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisSourceResponse source,
        org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope temporalScope,
        @Schema(nullable = true) Integer effectiveFromEpisodeNo,
        @Schema(nullable = true, description = "보존된 내부 추출 원본. BOOK 주장 상태와 sourceCandidates를 포함합니다.")
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Map<String, Object> rawExtractionJson
) {
    public WorkerWorldSettingCandidatePayload(UUID candidateId, UUID workId, UUID sourceEpisodeId, WorldSettingCategory category, String subjectName, String scopeName, String settingName, String extractedValue, List<EvidenceSpan> evidenceSpans, BigDecimal extractionConfidence, org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisSourceResponse source, org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope temporalScope, Integer effectiveFromEpisodeNo) {
        this(candidateId, workId, sourceEpisodeId, category, subjectName, scopeName, settingName, extractedValue, evidenceSpans, extractionConfidence, source, temporalScope, effectiveFromEpisodeNo, null);
    }
    public WorkerWorldSettingCandidatePayload(UUID candidateId, UUID workId, UUID sourceEpisodeId, WorldSettingCategory category, String subjectName, String scopeName, String settingName, String extractedValue, List<EvidenceSpan> evidenceSpans, BigDecimal extractionConfidence, org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisSourceResponse source) {
        this(candidateId, workId, sourceEpisodeId, category, subjectName, scopeName, settingName, extractedValue, evidenceSpans, extractionConfidence, source, source != null && source.sourceKind() == org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.SETTING_BOOK
                ? org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope.UNKNOWN : org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope.PRESENT, null);
    }
    public WorkerWorldSettingCandidatePayload(UUID candidateId, UUID workId, UUID sourceEpisodeId, WorldSettingCategory category, String subjectName, String scopeName, String settingName, String extractedValue, List<EvidenceSpan> evidenceSpans, BigDecimal extractionConfidence) {
        this(candidateId, workId, sourceEpisodeId, category, subjectName, scopeName, settingName, extractedValue, evidenceSpans, extractionConfidence, null, org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope.PRESENT, null);
    }


    public record EvidenceSpan(
            String quote,
            Integer startOffset,
            Integer endOffset
    ) {
    }
}
