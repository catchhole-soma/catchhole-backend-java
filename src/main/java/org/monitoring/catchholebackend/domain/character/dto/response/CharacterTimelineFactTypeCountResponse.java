package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;

@Schema(description = "캐릭터 타임라인 Fact 유형별 개수")
public record CharacterTimelineFactTypeCountResponse(
        @Schema(description = "Fact 유형", example = "STATUS")
        CharacterFactType factType,

        @Schema(description = "사용자용 Fact 유형 표시명", example = "상태")
        String factTypeLabel,

        @Schema(description = "현재·과거를 모두 포함한 Fact 개수", example = "7")
        long count
) {
}
