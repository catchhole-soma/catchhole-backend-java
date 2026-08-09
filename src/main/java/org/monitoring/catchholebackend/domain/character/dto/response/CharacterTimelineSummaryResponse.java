package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.CharacterTimelineFactFilter;

@Schema(description = "캐릭터 설정 이력 타임라인 요약")
public record CharacterTimelineSummaryResponse(
        @Schema(description = "캐릭터 ID")
        UUID characterId,

        @Schema(description = "캐릭터 이름", example = "수아")
        String characterName,

        @Schema(description = "첫 등장 회차 번호", example = "1", nullable = true)
        Integer firstAppearanceEpisodeNo,

        @Schema(description = "지원 유형 전체 Fact 개수", example = "42")
        long totalFactCount,

        @Schema(description = "지원 유형 Fact가 존재하는 전체 회차 개수", example = "18")
        long totalEpisodeCount,

        @Schema(description = "적용된 타임라인 유형 필터", example = "ALL")
        CharacterTimelineFactFilter appliedFactType,

        @Schema(description = "현재 필터에 해당하는 Fact 개수", example = "42")
        long filteredFactCount,

        @Schema(description = "지원 유형별 Fact 개수. 개수가 0인 유형도 포함")
        List<CharacterTimelineFactTypeCountResponse> factTypeCounts,

        @Schema(description = "현재 필터에서 Fact가 존재하는 회차 목록")
        List<CharacterTimelineEpisodeResponse> episodes,

        @Schema(description = "현재 필터에 해당하는 출처 회차 없는 수동 Fact 개수", example = "2")
        long manualFactCount
) {
}
