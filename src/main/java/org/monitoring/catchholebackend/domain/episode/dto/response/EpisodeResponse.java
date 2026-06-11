package org.monitoring.catchholebackend.domain.episode.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.episode.entity.EpisodeStatus;

@Schema(description = "회차 상세 응답")
public record EpisodeResponse(
        @Schema(description = "회차 ID", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID id,

        @Schema(description = "작품 ID", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID workId,

        @Schema(description = "원본 업로드 파일 ID", example = "550e8400-e29b-41d4-a716-446655440000", nullable = true)
        UUID sourceFileId,

        @Schema(description = "회차 번호", example = "159")
        int episodeNo,

        @Schema(description = "회차 제목", example = "운명의 실타래", nullable = true)
        String title,

        @Schema(description = "원문 S3 key", example = "works/{workId}/episodes/{episodeId}.txt", nullable = true)
        String contentS3Key,

        @Schema(description = "원문 S3 version", example = "3Lgk4...", nullable = true)
        String contentS3Version,

        @Schema(description = "원문 해시", example = "9f86d081884c7d659a2feaa0c55ad015...", nullable = true)
        String contentHash,

        @Schema(description = "글자 수", example = "6782")
        int charCount,

        @Schema(description = "회차 상태", example = "UPLOADED")
        EpisodeStatus status,

        @Schema(description = "회차 생성 시각", example = "2026-06-11T16:30:00")
        LocalDateTime createdAt,

        @Schema(description = "회차 수정 시각", example = "2026-06-11T16:30:00")
        LocalDateTime updatedAt
) {
}
