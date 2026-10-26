package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;

@Schema(description = "원문 없이 분석 진행을 표시하는 고정 대상 회차")
public record AnalysisJobProgressEpisodeResponse(
        @Schema(description = "대상 회차 ID") UUID id,
        @Schema(description = "회차 번호") Integer episodeNo,
        @Schema(description = "회차 제목", nullable = true) String title,
        @Schema(description = "현재 회차 상태") EpisodeStatus status,
        @Schema(description = "회차 최종 변경 시각") LocalDateTime updatedAt
) {}
