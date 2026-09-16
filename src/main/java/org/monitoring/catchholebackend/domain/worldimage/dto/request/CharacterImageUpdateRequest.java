package org.monitoring.catchholebackend.domain.worldimage.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@Schema(description = "종족 도감·개인 이미지·공통 기본 중 하나를 선택. 모두 비어 있으면 확정 종족에 따른 자동 연결로 돌아갑니다.")
public record CharacterImageUpdateRequest(
        @Size(max = 100) @Schema(nullable = true) String catalogId,
        @NotNull @Min(0) @Schema(description = "현재 이미지 선택 version", requiredMode = Schema.RequiredMode.REQUIRED) Long version,
        @Schema(nullable = true) UUID privateImageId,
        @Schema(description = "종족 정보와 관계없이 공통 기본 이미지 사용. 생략하면 false", defaultValue = "false") Boolean useDefault
) {}
