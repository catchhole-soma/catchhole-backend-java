package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "캐릭터 타임라인 하위 설정 항목 집계")
public record CharacterTimelineFactKeyCountResponse(
        @Schema(description = "필터 식별에 사용하는 canonical Fact key", example = "stats.strength")
        String factKey,

        @Schema(description = "사용자용 설정명", example = "근력")
        String displayName,

        @Schema(description = "캐릭터 전체 이력에서 이 key에 해당하는 Fact 개수", example = "4")
        long count
) {
}
