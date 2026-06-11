package org.monitoring.catchholebackend.domain.episode.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.monitoring.catchholebackend.domain.upload.entity.UploadType;

@Schema(description = "회차 업로드 요청 메타데이터")
public record EpisodeUploadRequest(
        @Schema(description = "업로드 방식", example = "SINGLE_EPISODE")
        @NotNull(message = "업로드 방식은 필수입니다.")
        UploadType uploadType,

        @Schema(description = "단일 회차 업로드 시 사용자가 입력한 회차 번호", example = "159", nullable = true)
        @Min(value = 1, message = "회차 번호는 1 이상이어야 합니다.")
        Integer episodeNo,

        @Schema(description = "단일 회차 업로드 시 사용자가 입력한 회차 제목", example = "운명의 실타래", nullable = true)
        @Size(max = 100, message = "회차 제목은 100자 이하로 입력해주세요.")
        String title
) {
}
