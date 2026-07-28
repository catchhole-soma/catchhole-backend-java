package org.monitoring.catchholebackend.domain.episode.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "회차 제목 수정 요청")
public record EpisodeTitleUpdateRequest(
        @Schema(description = "회차 제목. 비어 있으면 제목 없음으로 저장합니다.", nullable = true)
        @Size(max = 100, message = "회차 제목은 100자 이하로 입력해주세요.")
        String title
) {
}
