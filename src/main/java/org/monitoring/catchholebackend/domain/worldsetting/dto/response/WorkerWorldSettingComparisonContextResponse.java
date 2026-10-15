package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "Worker 세계관 설정 상세 비교 문맥")
public record WorkerWorldSettingComparisonContextResponse(
        WorkerWorldSettingCandidatePayload candidate,
        UUID exactTargetWorldSettingId,
        List<Target> targets
) {

    public record Target(
            UUID worldSettingId,
            String subjectName,
            List<WorldSettingPropertyResponse> properties,
            long version,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String provisionalSubjectKey,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) List<WorkerWorldSettingProtectedPathPayload> protectedPaths
    ) {
        public Target(UUID worldSettingId, String subjectName, List<WorldSettingPropertyResponse> properties, long version, String provisionalSubjectKey) {
            this(worldSettingId, subjectName, properties, version, provisionalSubjectKey, List.of());
        }
        public Target(UUID worldSettingId, String subjectName, List<WorldSettingPropertyResponse> properties, long version) {
            this(worldSettingId, subjectName, properties, version, null, List.of());
        }
    }
}
