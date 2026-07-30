package org.monitoring.catchholebackend.domain.upload.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "설정집 원문 수정 요청")
public record SettingBookUpdateRequest(
        @Schema(description = "수정할 설정집 전체 원문", example = "세계관 규칙과 인물 설정 원문")
        @NotBlank(message = "설정집 원문은 필수입니다.")
        @Size(max = 10485760, message = "설정집 원문은 10MB 이하여야 합니다.")
        String content
) {
}
