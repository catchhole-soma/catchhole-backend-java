package org.monitoring.catchholebackend.domain.character.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

@Schema(description = "같은 이름의 캐릭터 설정 후보 그룹 전체 확정 요청")
public record SettingCandidateGroupConfirmRequest(
        @jakarta.validation.constraints.NotNull UUID batchId,
        @NotEmpty List<@Valid SettingCandidateGroupConfirmDecision> candidates
) {
}
