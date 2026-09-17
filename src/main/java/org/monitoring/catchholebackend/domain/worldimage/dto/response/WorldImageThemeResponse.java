package org.monitoring.catchholebackend.domain.worldimage.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "작품 장르에 맞는 초기 분류 8칸과 기본 이미지 7칸")
public record WorldImageThemeResponse(
        @Schema(description = "여러 장르가 공유할 수 있는 테마 ID", example = "modern-common") String theme,
        @Schema(description = "분류 코드 및 ALL별 초기 이미지") Map<String, WorldSettingImageResponse> overview,
        @Schema(description = "분류 코드별 기본 이미지") Map<String, WorldSettingImageResponse> defaults
) {}

