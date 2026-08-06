package org.monitoring.catchholebackend.domain.worldsetting.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@Schema(description = "세계관 설정 속성 추가 요청")
public record WorldSettingPropertyCreateRequest(
        @NotBlank
        @Size(max = 100)
        @Schema(description = "추가할 설정명", example = "사회 구조")
        String settingName,

        @NotBlank
        @Schema(description = "추가할 설정값", example = "부족 단위로 생활")
        String settingValue,

        @NotNull
        @PositiveOrZero
        @Schema(description = "화면에서 조회한 현재 버전", example = "3")
        Long version
) {
}
