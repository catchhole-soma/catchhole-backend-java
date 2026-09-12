package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSubjectResolutionType;

@Schema(description = "Worker canonical 주체 해소 저장 결과")
public record WorkerWorldSettingSubjectResolutionResponse(
        List<ResolvedSubject> resolutions
) {

    public record ResolvedSubject(
            UUID candidateId,
            WorldSettingSubjectResolutionType resolutionType,
            String canonicalSubjectKey,
            String canonicalSubjectName,
            List<UUID> targetWorldSettingIds,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<String> provisionalSubjectKeys
    ) {
        public ResolvedSubject(UUID candidateId, WorldSettingSubjectResolutionType resolutionType,
                String canonicalSubjectKey, String canonicalSubjectName, List<UUID> targetWorldSettingIds) {
            this(candidateId, resolutionType, canonicalSubjectKey, canonicalSubjectName, targetWorldSettingIds, null);
        }
    }
}
