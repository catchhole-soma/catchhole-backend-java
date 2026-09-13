package org.monitoring.catchholebackend.domain.character.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactConfirmApplicationMode;

@Schema(description = "캐릭터 설정 후보 확정 방식")
public record SettingCandidateConfirmRequest(
        @Schema(description = "AI 제안을 현재 snapshot에 적용할지, 이력으로만 남길지 선택", example = "APPLY_PROPOSAL")
        CharacterFactConfirmApplicationMode applicationMode,

        @Schema(description = "화면에서 확인한 비교 기준 snapshot version", example = "3", nullable = true)
        Long baseSnapshotVersion,
        @Schema(description = "사용자가 수정한 값을 현재 실제 설정에 직접 검증하여 적용할지 여부. 누적 분석의 명시적 수정 확정에만 사용합니다.")
        Boolean applyEditedValue
) {
    public SettingCandidateConfirmRequest(CharacterFactConfirmApplicationMode applicationMode, Long baseSnapshotVersion) {
        this(applicationMode, baseSnapshotVersion, null);
    }
}
