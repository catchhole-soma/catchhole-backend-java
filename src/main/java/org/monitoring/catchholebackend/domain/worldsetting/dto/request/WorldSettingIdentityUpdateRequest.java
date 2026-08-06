package org.monitoring.catchholebackend.domain.worldsetting.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;

@Schema(description = "세계관 대상 정보 수정 요청")
public record WorldSettingIdentityUpdateRequest(
        @NotNull
        @Schema(description = "수정할 세계관 분류", example = "RACE")
        WorldSettingCategory category,

        @NotBlank
        @Size(max = 100)
        @Schema(description = "수정할 대상명", example = "북부 바바리안")
        String subjectName,

        @PositiveOrZero
        @Schema(description = "화면에서 조회한 현재 버전", example = "3")
        long version
) {
}
