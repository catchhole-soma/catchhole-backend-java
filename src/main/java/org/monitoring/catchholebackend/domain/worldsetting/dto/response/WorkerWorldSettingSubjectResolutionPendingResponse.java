package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
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
            UUID sourceEpisodeId,
            WorldSettingCategory category,
            String subjectName,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            List<WorkerWorldSettingCandidatePayload.EvidenceSpan> evidenceSpans,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            Integer sourceEpisodeNo
    ) {
        public Candidate(UUID candidateId, UUID sourceEpisodeId, WorldSettingCategory category, String subjectName) {
            this(candidateId, sourceEpisodeId, category, subjectName, null, null);
        }
    }
}
