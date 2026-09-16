package org.monitoring.catchholebackend.domain.worldimage.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@Schema(description = "공용 catalogId 또는 개인 privateImageId 중 하나를 선택. 둘 다 null이면 기본 이미지로 되돌립니다. useAutomatic=true이면 현재 이름으로 자동 연결을 다시 저장합니다.")
public record WorldSettingImageUpdateRequest(
        @Size(max = 100) @Schema(nullable = true) String catalogId,
        @NotNull @Min(0) @Schema(description = "현재 이미지 선택 version", requiredMode = Schema.RequiredMode.REQUIRED) Long version,
        @Schema(nullable = true) UUID privateImageId,
        @Schema(description = "현재 이름·분류·장르로 자동 연결 복귀. 다른 이미지 선택과 함께 보낼 수 없습니다.") Boolean useAutomatic
) {
    public WorldSettingImageUpdateRequest(String catalogId, Long version, UUID privateImageId) {
        this(catalogId, version, privateImageId, false);
    }
}
