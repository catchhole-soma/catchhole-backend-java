package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "캐릭터 타임라인 cursor 묶음")
public record CharacterTimelineResponse(
        @Schema(description = "고정 정렬된 Fact 목록")
        List<CharacterTimelineFactResponse> content,

        @Schema(description = "다음 묶음 cursor. 마지막 묶음이면 null", nullable = true)
        String nextCursor,

        @Schema(description = "다음 묶음 존재 여부", example = "true")
        boolean hasNext,

        @Schema(description = "이번 응답에 실제 포함된 Fact 개수", example = "20")
        int size
) {
}
