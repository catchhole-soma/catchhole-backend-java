package org.monitoring.catchholebackend.domain.character.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactConfirmApplicationMode;

@Schema(description = "캐릭터 설정 후보 그룹 안 후보 한 건의 확정 방식")
public record SettingCandidateGroupConfirmDecision(
        @NotNull UUID candidateId,
        @NotNull CharacterFactConfirmApplicationMode applicationMode,
        @Schema(nullable = true) Long baseSnapshotVersion,
        @Schema(description = "사용자가 수정한 값을 현재 실제 설정에 직접 검증하여 적용할지 여부. 저장된 reviewedApplicationMode가 있는 초안에 사용합니다.")
        Boolean applyEditedValue,
        @Schema(description = "직접 저장한 검토 결정을 확정할 때 마지막으로 확인한 후보 updatedAt. 수정 초안 확정과 acceptDisplayedResults 요청의 모든 후보에 필요합니다.", nullable = true)
        LocalDateTime expectedUpdatedAt
) {
    public SettingCandidateGroupConfirmDecision(UUID candidateId, CharacterFactConfirmApplicationMode applicationMode, Long baseSnapshotVersion) {
        this(candidateId, applicationMode, baseSnapshotVersion, null, null);
    }
    public SettingCandidateGroupConfirmDecision(UUID candidateId, CharacterFactConfirmApplicationMode applicationMode,
            Long baseSnapshotVersion, Boolean applyEditedValue) {
        this(candidateId, applicationMode, baseSnapshotVersion, applyEditedValue, null);
    }
}
