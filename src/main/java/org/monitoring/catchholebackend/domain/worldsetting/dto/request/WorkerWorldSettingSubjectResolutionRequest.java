package org.monitoring.catchholebackend.domain.worldsetting.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record WorkerWorldSettingSubjectResolutionRequest(
        @NotEmpty(message = "주체 해소 결과는 한 개 이상이어야 합니다.")
        @Size(max = 500, message = "주체 해소 결과는 최대 500개입니다.")
        List<@Valid SubjectResolutionInput> resolutions
) {

    public record SubjectResolutionInput(
            @NotNull UUID candidateId,
            @NotNull
            @Size(max = 20, message = "주체 후보 설정은 최대 20개입니다.")
            List<@NotNull UUID> targetWorldSettingIds
    ) {
    }
}
