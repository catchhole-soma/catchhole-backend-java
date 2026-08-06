package org.monitoring.catchholebackend.domain.worldsetting.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;

@Schema(description = "세계관 설정 후보 비교 대상 수정 요청")
public record WorldSettingCandidateUpdateRequest(
        @NotNull
        @Schema(description = "수정할 분류", example = "RACE")
        WorldSettingCategory category,

        @NotBlank
        @Size(max = 100)
        @Schema(description = "수정할 대상명", example = "바바리안")
        String subjectName,

        @NotBlank
        @Size(max = 100)
        @Schema(description = "수정할 설정명", example = "서식지")
        String settingName
) {
}
