package org.monitoring.catchholebackend.domain.worldsetting.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@Schema(description = "세계관 설정 속성 수정 요청")
public record WorldSettingPropertyUpdateRequest(
        @NotBlank
        @Size(max = 100)
        @Schema(description = "현재 설정명", example = "서식지")
        String currentSettingName,

        @NotBlank
        @Size(max = 100)
        @Schema(description = "저장할 설정명", example = "생활 지역")
        String settingName,

        @NotBlank
        @Schema(description = "저장할 설정값", example = "북부 혹한 지역")
        String settingValue,

        @PositiveOrZero
        @Schema(description = "화면에서 조회한 현재 버전", example = "3")
        long version
) {
}
