package org.monitoring.catchholebackend.domain.worldimage.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "세계관 대상의 표시 이미지. 설정 내용·분석 버전과 독립적으로 저장합니다.")
public record WorldSettingImageResponse(
        @Schema(nullable = true) String catalogId,
        @Schema(nullable = true) String name,
        @Schema(nullable = true, description = "480×320 썸네일 API 상대 경로") String thumbnailUrl,
        @Schema(nullable = true, description = "960×640 이미지 API 상대 경로") String imageUrl,
        @Schema(allowableValues = {"DEFAULT", "MANUAL"}) String source,
        @Schema(description = "이미지 선택의 독립 버전. 설정 수정 version과 다릅니다.") long version
) {}
