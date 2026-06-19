package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "AI Worker 회차 분석 대상 payload")
public record WorkerAnalysisEpisodePayload(
        @Schema(description = "회차 ID")
        UUID episodeId,

        @Schema(description = "회차 번호", example = "1")
        int episodeNo,

        @Schema(description = "회차 제목", nullable = true)
        String title,

        @Schema(description = "회차 원문 S3 key")
        String contentS3Key,

        @Schema(description = "회차 원문 S3 version ID", nullable = true)
        String contentS3Version,

        @Schema(description = "회차 원문 SHA-256 hash", nullable = true)
        String contentHash,

        @Schema(description = "회차 원문 글자 수", example = "12345")
        int charCount
) {
}
