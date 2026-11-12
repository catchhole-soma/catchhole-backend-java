package org.monitoring.catchholebackend.domain.work.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.monitoring.catchholebackend.domain.work.type.WorkCoverAction;

@Schema(description = "작품 정보와 표지를 함께 저장하는 요청")
public record WorkCoverUpdateRequest(
        @Valid @NotNull @Schema(description = "함께 저장할 작품 정보", requiredMode = Schema.RequiredMode.REQUIRED)
        WorkUpdateRequest work,
        @NotNull @Schema(description = "REPLACE는 image 파일 필수, REMOVE는 파일 없이 기본 표지로 복귀", requiredMode = Schema.RequiredMode.REQUIRED)
        WorkCoverAction action,
        @NotNull @Min(0) @Schema(description = "편집 시작 시 확인한 표지 버전", requiredMode = Schema.RequiredMode.REQUIRED)
        Long expectedCoverVersion
) {}
