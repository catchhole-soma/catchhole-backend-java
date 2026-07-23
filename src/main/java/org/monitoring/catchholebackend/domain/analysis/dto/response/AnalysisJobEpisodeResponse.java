package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;

@Schema(description = "분석 작업 대상 회차의 진행 상태")
public record AnalysisJobEpisodeResponse(
        @Schema(description = "회차 ID")
        UUID id,

        @Schema(description = "회차 번호", example = "159")
        int episodeNo,

        @Schema(description = "회차 제목", nullable = true)
        String title,

        @Schema(description = "회차 처리 상태", example = "ANALYZING")
        EpisodeStatus status,

        @Schema(description = "회차별 실패 사유", nullable = true)
        String errorMessage,

        @Schema(description = "회차 상태 수정 시각")
        LocalDateTime updatedAt
) {
}
