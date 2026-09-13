package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "Worker 세계관 설정 묶음 비교 문맥")
public record WorkerWorldSettingComparisonBatchContextResponse(
        UUID comparisonBatchId,
        List<WorkerWorldSettingComparisonBatchPayload.Candidate> candidates,
        List<ExactTarget> exactTargets,
        List<WorkerWorldSettingComparisonContextResponse.Target> targets,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String contextToken,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisContextPayload analysisContext
) {
    public WorkerWorldSettingComparisonBatchContextResponse(UUID comparisonBatchId,
            List<WorkerWorldSettingComparisonBatchPayload.Candidate> candidates, List<ExactTarget> exactTargets,
            List<WorkerWorldSettingComparisonContextResponse.Target> targets) {
        this(comparisonBatchId, candidates, exactTargets, targets, null, null);
    }

    public record ExactTarget(
            String candidateRef,
            @Schema(nullable = true) UUID worldSettingId,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String provisionalSubjectKey
    ) {
        public ExactTarget(String candidateRef, UUID worldSettingId) {
            this(candidateRef, worldSettingId, null);
        }
    }
}
