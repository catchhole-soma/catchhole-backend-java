package org.monitoring.catchholebackend.domain.episode.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "사용자가 확정한 업로드 회차 정보")
public record EpisodeUploadConfirmationRequest(
        @Schema(description = "감지 결과에서의 0부터 시작하는 순서", example = "0")
        @NotNull(message = "감지 순서는 필수입니다.")
        @Min(value = 0, message = "감지 순서는 0 이상이어야 합니다.")
        Integer detectionOrder,

        @Schema(description = "사용자가 확정한 회차 번호", example = "159")
        @NotNull(message = "회차 번호는 필수입니다.")
        @Min(value = 1, message = "회차 번호는 1 이상이어야 합니다.")
        Integer episodeNo,

        @Schema(description = "사용자가 확정한 회차 제목", example = "운명의 실타래", nullable = true)
        @Size(max = 100, message = "회차 제목은 100자 이하로 입력해주세요.")
        String title
) {
}
