package org.monitoring.catchholebackend.domain.worldsetting.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;

public record WorkerWorldSettingSubjectResolutionRequest(
        @NotEmpty(message = "주체 해소 결과는 한 개 이상이어야 합니다.")
        @Size(max = 500, message = "주체 해소 결과는 최대 500개입니다.")
        List<@Valid SubjectResolutionInput> resolutions
) {

    public record SubjectResolutionInput(
            @NotNull UUID candidateId,
            @NotNull
            @Size(max = 20, message = "주체 후보 설정은 최대 20개입니다.")
            List<@NotNull UUID> targetWorldSettingIds,
            @Size(max = 20) List<String> provisionalSubjectKeys,
            Boolean ambiguous,
            AnalysisFailureCode failureCode
    ) {
        public SubjectResolutionInput(UUID candidateId, List<UUID> targetWorldSettingIds,
                List<String> provisionalSubjectKeys, Boolean ambiguous) {
            this(candidateId, targetWorldSettingIds, provisionalSubjectKeys, ambiguous, null);
        }
        public SubjectResolutionInput(UUID candidateId, List<UUID> targetWorldSettingIds) {
            this(candidateId, targetWorldSettingIds, List.of(), false);
        }
        public SubjectResolutionInput {
            ambiguous = Boolean.TRUE.equals(ambiguous);
            provisionalSubjectKeys = provisionalSubjectKeys == null ? List.of() : List.copyOf(provisionalSubjectKeys);
        }
    }
}
