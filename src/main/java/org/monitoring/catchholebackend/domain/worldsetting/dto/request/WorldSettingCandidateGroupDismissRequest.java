package org.monitoring.catchholebackend.domain.worldsetting.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

@Schema(description = "같은 세계관 대상 그룹의 선택 key 제외 요청")
public record WorldSettingCandidateGroupDismissRequest(
        @NotNull UUID batchId,
        @NotEmpty @Size(max = 100) List<@NotNull UUID> candidateIds,
        String reviewNote
) {
}
