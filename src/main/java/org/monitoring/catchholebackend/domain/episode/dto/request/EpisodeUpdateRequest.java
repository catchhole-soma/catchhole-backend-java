package org.monitoring.catchholebackend.domain.episode.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "회차 원문 수정 요청")
public record EpisodeUpdateRequest(
        @Schema(description = "회차 번호", example = "159")
        @NotNull(message = "회차 번호는 필수입니다.")
        @Min(value = 1, message = "회차 번호는 1 이상이어야 합니다.")
        Integer episodeNo,

        @Schema(description = "회차 제목", example = "운명의 실타래", nullable = true)
        @Size(max = 100, message = "회차 제목은 100자 이하로 입력해주세요.")
        String title,

        @Schema(description = "회차 원문", example = "어둠이 짙게 깔린 성벽 너머로 차가운 바람이 불어왔다.")
        @NotBlank(message = "회차 원문은 필수입니다.")
        @Size(max = 100000, message = "회차 원문은 100000자 이하로 입력해주세요.")
        String content
) {
}
