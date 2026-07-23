package org.monitoring.catchholebackend.domain.episode.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeUploadType;

@Schema(description = "영구 저장 전 회차 표기 감지 결과")
public record EpisodeDetectionResponse(
        @Schema(
                description = "업로드 방식",
                example = "MULTI_EPISODE_SINGLE_FILE",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        EpisodeUploadType uploadType,

        @Schema(description = "감지된 회차 수", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
        int episodeCount,

        @Schema(
                description = "감지된 전체 본문 글자 수",
                example = "20346",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        int totalCharCount,

        @Schema(description = "원문 순서의 감지 회차 목록", requiredMode = Schema.RequiredMode.REQUIRED)
        List<DetectedEpisodeResponse> detectedEpisodes
) {
}
