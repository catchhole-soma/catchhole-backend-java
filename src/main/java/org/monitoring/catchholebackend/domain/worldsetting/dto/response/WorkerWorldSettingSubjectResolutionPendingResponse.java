package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;

@Schema(description = "Worker canonical 주체 해소가 필요한 세계관 후보")
public record WorkerWorldSettingSubjectResolutionPendingResponse(
        List<Candidate> candidates
) {

    public record Candidate(
            UUID candidateId,
            UUID sourceEpisodeId,
            WorldSettingCategory category,
            String subjectName
    ) {
    }
}
