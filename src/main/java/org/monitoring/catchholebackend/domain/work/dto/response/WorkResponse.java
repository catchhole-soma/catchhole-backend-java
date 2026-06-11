package org.monitoring.catchholebackend.domain.work.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.work.entity.WorkStatus;

@Schema(description = "작품 응답")
public record WorkResponse(
        @Schema(description = "작품 ID", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID id,

        @Schema(description = "작품 제목", example = "빛나는 검사 로맨스")
        String title,

        @Schema(description = "작품 장르", example = "로맨스", nullable = true)
        String genre,

        @Schema(description = "작품 설명", example = "검사 주인공의 성장과 로맨스를 다룬 웹소설입니다.", nullable = true)
        String description,

        @Schema(description = "작품 상태", example = "ACTIVE")
        WorkStatus status,

        @Schema(description = "가장 최근 회차 번호", example = "0")
        int latestEpisodeNo,

        @Schema(description = "작품 생성 시각", example = "2026-06-11T16:30:00")
        LocalDateTime createdAt,

        @Schema(description = "작품 수정 시각", example = "2026-06-11T16:30:00")
        LocalDateTime updatedAt
) {
}
