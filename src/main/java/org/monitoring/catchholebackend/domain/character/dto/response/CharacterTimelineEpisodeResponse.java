package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "캐릭터 타임라인 회차 바로가기 요약")
public record CharacterTimelineEpisodeResponse(
        @Schema(description = "출처 회차 ID")
        UUID episodeId,

        @Schema(description = "출처 회차 번호", example = "12")
        int episodeNo,

        @Schema(description = "현재 필터에서 이 회차에 포함된 Fact 개수", example = "3")
        long factCount
) {
}
