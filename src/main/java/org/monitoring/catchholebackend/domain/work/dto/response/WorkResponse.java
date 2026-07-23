package org.monitoring.catchholebackend.domain.work.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "작품 응답")
public record WorkResponse(
        @Schema(
                description = "작품 ID",
                example = "550e8400-e29b-41d4-a716-446655440000",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        UUID id,

        @Schema(
                description = "작품 제목",
                example = "빛나는 검사 로맨스",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String title,

        @Schema(
                description = "작품 장르",
                example = "로맨스",
                allowableValues = {
                        "판타지", "로맨스", "추리", "코미디", "SF",
                        "스포츠", "호러", "무협", "일상", "기타"
                },
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String genre,

        @Schema(
                description = "작품 목록에 한 줄로 표시할 짧은 소개",
                example = "검사 주인공의 성장 로맨스",
                maxLength = 20,
                nullable = true
        )
        String description,

        @Schema(
                description = "가장 최근 회차 번호. 등록된 회차가 없으면 0",
                example = "0",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        int latestEpisodeNo,

        @Schema(
                description = "작품 생성 시각",
                example = "2026-06-11T16:30:00",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        LocalDateTime createdAt,

        @Schema(
                description = "작품 수정 시각",
                example = "2026-06-11T16:30:00",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        LocalDateTime updatedAt
) {
}
