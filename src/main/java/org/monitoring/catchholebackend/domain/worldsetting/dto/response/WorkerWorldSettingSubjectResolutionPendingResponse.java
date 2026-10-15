package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;

@Schema(description = "Worker canonical 주체 해소가 필요한 세계관 후보")
public record WorkerWorldSettingSubjectResolutionPendingResponse(
        List<Candidate> candidates,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisContextPayload analysisContext
) {
    public WorkerWorldSettingSubjectResolutionPendingResponse(List<Candidate> candidates) {
        this(candidates, null);
    }

    public record Candidate(
            UUID candidateId,
            @io.swagger.v3.oas.annotations.media.Schema(nullable = true) UUID sourceEpisodeId,
            WorldSettingCategory category,
            String subjectName,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            List<WorkerWorldSettingCandidatePayload.EvidenceSpan> evidenceSpans,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            Integer sourceEpisodeNo,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisSourceResponse source,
        @Schema(nullable = true, description = "보존된 내부 추출 원본과 BOOK 주장 상태")
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Map<String, Object> rawExtractionJson
) {
    public Candidate(UUID candidateId, UUID sourceEpisodeId, WorldSettingCategory category, String subjectName, List<WorkerWorldSettingCandidatePayload.EvidenceSpan> evidenceSpans, Integer sourceEpisodeNo, org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisSourceResponse source) {
        this(candidateId, sourceEpisodeId, category, subjectName, evidenceSpans, sourceEpisodeNo, source, null);
    }
    public Candidate(UUID candidateId, UUID sourceEpisodeId, WorldSettingCategory category, String subjectName, List<WorkerWorldSettingCandidatePayload.EvidenceSpan> evidenceSpans, Integer sourceEpisodeNo) {
        this(candidateId, sourceEpisodeId, category, subjectName, evidenceSpans, sourceEpisodeNo, null);
    }

        public Candidate(UUID candidateId, UUID sourceEpisodeId, WorldSettingCategory category, String subjectName) {
            this(candidateId, sourceEpisodeId, category, subjectName, null, null, null);
        }
    }
}
