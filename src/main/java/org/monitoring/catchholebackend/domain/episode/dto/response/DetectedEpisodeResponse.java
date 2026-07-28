package org.monitoring.catchholebackend.domain.episode.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "영구 저장 전 감지된 회차")
public record DetectedEpisodeResponse(
        @Schema(
                description = "감지 결과에서의 0부터 시작하는 순서",
                example = "0",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        int detectionOrder,

        @Schema(
                description = "원본 파일의 0부터 시작하는 순서",
                example = "0",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        int sourceFileIndex,

        @Schema(description = "감지한 회차 번호", example = "159", requiredMode = Schema.RequiredMode.REQUIRED)
        int episodeNo,

        @Schema(
                description = "원문 제목 행에서 감지한 제목",
                example = "운명의 실타래",
                nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String title,

        @Schema(
                description = "원본에서 감지한 회차 제목 행",
                example = "제 159화 운명의 실타래",
                nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String sourceHeading,

        @Schema(description = "회차 본문 글자 수", example = "6782", requiredMode = Schema.RequiredMode.REQUIRED)
        int charCount,

        @Schema(description = "고정된 감지 경계 안의 회차 본문", requiredMode = Schema.RequiredMode.REQUIRED)
        String content
) {
}
