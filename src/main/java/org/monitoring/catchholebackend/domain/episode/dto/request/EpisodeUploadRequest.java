package org.monitoring.catchholebackend.domain.episode.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeUploadType;

@Schema(description = "회차 업로드 요청 메타데이터")
public record EpisodeUploadRequest(
        @Schema(description = "업로드 방식", example = "SINGLE_EPISODE")
        @NotNull(message = "업로드 방식은 필수입니다.")
        EpisodeUploadType uploadType,

        @Schema(description = "단일 회차 업로드 시 사용자가 입력한 회차 번호", example = "159", nullable = true)
        @Min(value = 1, message = "회차 번호는 1 이상이어야 합니다.")
        Integer singleEpisodeNo,

        @Schema(description = "단일 회차 업로드 시 사용자가 입력한 회차 제목", example = "운명의 실타래", nullable = true)
        @Size(max = 100, message = "회차 제목은 100자 이하로 입력해주세요.")
        String singleEpisodeTitle,

        @Schema(description = "감지 후 사용자가 최종 확정한 회차 목록. 감지 결과 순서와 동일해야 합니다.")
        List<@NotNull(message = "확정한 회차 정보는 null일 수 없습니다.")
                @Valid EpisodeUploadConfirmationRequest> episodeConfirmations
) {
}
