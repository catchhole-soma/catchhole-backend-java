package org.monitoring.catchholebackend.domain.worldsetting.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;

@Schema(description = "세계관 설정 후보 최종 확정 요청")
public record WorldSettingCandidateConfirmRequest(
        @NotNull
        @Schema(description = "최종 반영 방식. EXCLUDE는 제외 API를 사용합니다.", example = "ADD")
        WorldSettingOperation operation,

        @NotNull
        @Schema(description = "최종 분류", example = "RACE")
        WorldSettingCategory category,

        @NotBlank
        @Size(max = 100)
        @Schema(description = "최종 대상명", example = "바바리안")
        String subjectName,

        @Size(max = 100)
        @Schema(description = "최종 선택적 한 단계 범위", nullable = true, example = "1층")
        String scopeName,

        @NotBlank
        @Size(max = 100)
        @Schema(description = "최종 설정명", example = "서식지")
        String settingName,

        @NotBlank
        @Schema(description = "최종 설정값", example = "혹한 지역")
        String value,

        @Schema(description = "서로 다른 추출 내용을 사용자가 최종값으로 정리했는지 여부", nullable = true)
        Boolean conflictResolved,

        @Size(max = 1000)
        @Schema(description = "선택 검토 메모", nullable = true)
        String reviewNote,
        @Schema(description = "기존 실제 대상의 선택 경로만 현재값으로 명시 반영합니다. 새 대상에는 사용하지 않습니다.") Boolean applyToCurrent,
        @Schema(description = "변경 전 실제 세계관 대상 version. applyToCurrent=true이면 필수입니다.", nullable = true) Long expectedWorldSettingVersion,
        @Schema(description = "확인한 후보 updatedAt. applyToCurrent=true이면 필수입니다.", nullable = true) java.time.LocalDateTime expectedUpdatedAt
) {

    public WorldSettingCandidateConfirmRequest(WorldSettingOperation operation, WorldSettingCategory category, String subjectName,
            String scopeName, String settingName, String value, Boolean conflictResolved, String reviewNote) {
        this(operation, category, subjectName, scopeName, settingName, value, conflictResolved, reviewNote, null, null, null);
    }
    public WorldSettingCandidateConfirmRequest(
            WorldSettingOperation operation,
            WorldSettingCategory category,
            String subjectName,
            String settingName,
            String value,
            Boolean conflictResolved,
            String reviewNote
    ) {
        this(operation, category, subjectName, null, settingName, value, conflictResolved, reviewNote, null, null, null);
    }
}
