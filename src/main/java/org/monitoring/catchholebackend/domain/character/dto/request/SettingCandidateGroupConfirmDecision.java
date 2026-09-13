package org.monitoring.catchholebackend.domain.character.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactConfirmApplicationMode;

@Schema(description = "캐릭터 설정 후보 그룹 안 후보 한 건의 확정 방식")
public record SettingCandidateGroupConfirmDecision(
        @NotNull UUID candidateId,
        @NotNull CharacterFactConfirmApplicationMode applicationMode,
        @Schema(nullable = true) Long baseSnapshotVersion,
        @Schema(description = "사용자가 수정한 값을 현재 실제 설정에 직접 검증하여 적용할지 여부. 누적 분석의 명시적 수정 확정에만 사용합니다.")
        Boolean applyEditedValue
) {
    public SettingCandidateGroupConfirmDecision(UUID candidateId, CharacterFactConfirmApplicationMode applicationMode, Long baseSnapshotVersion) {
        this(candidateId, applicationMode, baseSnapshotVersion, null);
    }
}
