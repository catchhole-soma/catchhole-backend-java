package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;

@Schema(description = "캐릭터 타임라인 종류별 선택 그룹")
public record CharacterTimelineFactFacetResponse(
        @Schema(description = "상위 Fact 유형", example = "STAT")
        CharacterFactType factType,

        @Schema(description = "사용자용 Fact 유형 표시명", example = "스탯")
        String factTypeLabel,

        @Schema(description = "캐릭터 전체 이력에서 이 유형에 해당하는 Fact 개수", example = "12")
        long count,

        @Schema(description = "캐릭터가 실제로 가진 하위 canonical Fact key 목록")
        List<CharacterTimelineFactKeyCountResponse> factKeys
) {
}
