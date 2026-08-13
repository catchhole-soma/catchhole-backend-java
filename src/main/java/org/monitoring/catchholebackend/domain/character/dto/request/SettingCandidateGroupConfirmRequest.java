package org.monitoring.catchholebackend.domain.character.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

@Schema(description = "같은 이름의 캐릭터 설정 후보 그룹 전체 확정 요청")
public record SettingCandidateGroupConfirmRequest(
        @NotNull UUID batchId,
        @NotEmpty List<@NotNull @Valid SettingCandidateGroupConfirmDecision> candidates
) {
}
