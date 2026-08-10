package org.monitoring.catchholebackend.domain.worldsetting.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

@Schema(description = "세계관 설정 후보 작가 수정안 저장 요청")
public record WorldSettingCandidateDecisionUpdateRequest(
        @NotNull UUID batchId,
        @NotEmpty @Size(max = 100) List<@Valid WorldSettingCandidateDecisionUpdateItem> candidates
) {
}
