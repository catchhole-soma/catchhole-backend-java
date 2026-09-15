package org.monitoring.catchholebackend.domain.worldimage.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;

@Schema(description = "선택할 수 있는 공용 세계관 대표 이미지")
public record WorldImageCatalogResponse(
        String id, WorldSettingCategory category, String name, List<String> aliases,
        @Schema(description = "480×320 썸네일의 API 상대 경로") String thumbnailUrl,
        @Schema(description = "960×640 이미지의 API 상대 경로") String imageUrl
) {}
