package org.monitoring.catchholebackend.domain.worldimage.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "세계관 대표 이미지 선택. catalogId=null이면 기본 이미지로 되돌립니다.")
public record WorldSettingImageUpdateRequest(
        @Size(max = 100) @Schema(nullable = true) String catalogId,
        @NotNull @Min(0) @Schema(description = "현재 이미지 선택 version", requiredMode = Schema.RequiredMode.REQUIRED) Long version
) {}
