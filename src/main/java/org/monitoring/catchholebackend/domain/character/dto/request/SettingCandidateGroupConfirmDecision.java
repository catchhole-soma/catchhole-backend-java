package org.monitoring.catchholebackend.domain.character.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactConfirmApplicationMode;

@Schema(description = "캐릭터 설정 후보 그룹 안 후보 한 건의 확정 방식")
public record SettingCandidateGroupConfirmDecision(
        @NotNull UUID candidateId,
        @NotNull CharacterFactConfirmApplicationMode applicationMode,
        @Schema(nullable = true) Long baseSnapshotVersion
) {
}
