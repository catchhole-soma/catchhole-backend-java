package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "Worker 세계관 설정 묶음 비교 문맥")
public record WorkerWorldSettingComparisonBatchContextResponse(
        UUID comparisonBatchId,
        List<WorkerWorldSettingComparisonBatchPayload.Candidate> candidates,
        List<ExactTarget> exactTargets,
        List<WorkerWorldSettingComparisonContextResponse.Target> targets
) {

    public record ExactTarget(
            String candidateRef,
            @Schema(nullable = true) UUID worldSettingId
    ) {
    }
}
