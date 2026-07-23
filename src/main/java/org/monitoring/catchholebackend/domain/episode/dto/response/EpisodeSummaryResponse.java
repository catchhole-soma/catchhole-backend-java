package org.monitoring.catchholebackend.domain.episode.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeAnalysisStatus;

@Schema(description = "회차 목록 응답")
public record EpisodeSummaryResponse(
        @Schema(description = "회차 ID", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID id,

        @Schema(description = "회차 번호", example = "159")
        int episodeNo,

        @Schema(description = "회차 제목", example = "운명의 실타래", nullable = true)
        String title,

        @Schema(description = "글자 수", example = "6782")
        int charCount,

        @Schema(description = "회차 상태", example = "UPLOADED")
        EpisodeStatus status,

        @Schema(description = "원본 파일명", example = "EP_159_운명의_실타래.txt", nullable = true)
        String originalFilename,

        @Schema(description = "원본이 포함된 업로드 배치 ID", nullable = true)
        UUID batchId,

        @Schema(description = "원고 목록용 분석 상태", example = "IN_PROGRESS")
        EpisodeAnalysisStatus analysisStatus,

        @Schema(description = "최신 유효 리포트의 미처리 항목 수", nullable = true)
        Integer unresolvedFindingCount,

        @Schema(description = "가장 최근 분석 작업 ID", nullable = true)
        UUID latestAnalysisJobId,

        @Schema(description = "원문 변경 시각")
        LocalDateTime contentUpdatedAt,

        @Schema(description = "회차 생성 시각", example = "2026-06-11T16:30:00")
        LocalDateTime createdAt,

        @Schema(description = "회차 수정 시각", example = "2026-06-11T16:30:00")
        LocalDateTime updatedAt
) {
}
