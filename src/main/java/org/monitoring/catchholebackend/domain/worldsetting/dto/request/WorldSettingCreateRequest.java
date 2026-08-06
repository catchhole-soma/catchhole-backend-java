package org.monitoring.catchholebackend.domain.worldsetting.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;

@Schema(description = "세계관 대상과 첫 설정 생성 요청")
public record WorldSettingCreateRequest(
        @NotNull
        @Schema(description = "세계관 분류", example = "RACE")
        WorldSettingCategory category,

        @NotBlank
        @Size(max = 100)
        @Schema(description = "대상명. 앞뒤 공백만 제거하고 내부 공백은 보존합니다.", example = "바바리안")
        String subjectName,

        @NotBlank
        @Size(max = 100)
        @Schema(description = "첫 설정명", example = "서식지")
        String settingName,

        @NotBlank
        @Schema(description = "첫 설정값", example = "혹한 지역")
        String settingValue
) {
}
