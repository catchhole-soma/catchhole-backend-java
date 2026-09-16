package org.monitoring.catchholebackend.domain.worldimage.dto.request;

import java.util.UUID;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "작품별 미처리 이미지 보정. apply=true일 때만 저장하고, 기본은 예상 결과 조회입니다.")
public record WorldImageBackfillRequest(
        @NotNull UUID workId,
        @NotNull Kind kind,
        @Min(1) @Max(500) Integer limit,
        Boolean apply
) {
    public enum Kind { CHARACTER, WORLD_SETTING }
}
