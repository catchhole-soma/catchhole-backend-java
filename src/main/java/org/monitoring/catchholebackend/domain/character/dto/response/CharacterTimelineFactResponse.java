package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterTimelineSourceType;

@Schema(description = "캐릭터 타임라인 Fact 항목")
public record CharacterTimelineFactResponse(
        @Schema(description = "CharacterFact ID")
        UUID characterFactId,

        @Schema(description = "Fact 유형", example = "STATUS")
        CharacterFactType factType,

        @Schema(description = "종류별 하위 필터 식별에 사용하는 canonical Fact key", example = "status.injury")
        String factKey,

        @Schema(description = "사용자용 Fact 유형 표시명", example = "상태")
        String factTypeLabel,

        @Schema(description = "사용자용 설정명", example = "부상")
        String displayName,

        @Schema(description = "사용자용 설정값", example = "오른발을 다침", nullable = true)
        String factValue,

        @Schema(description = "회차 추출 또는 수동 입력 출처", example = "EPISODE")
        CharacterTimelineSourceType sourceType,

        @Schema(description = "출처 회차 ID. 수동 Fact는 null", nullable = true)
        UUID sourceEpisodeId,

        @Schema(description = "출처 회차 번호. 수동 Fact는 null", example = "12", nullable = true)
        Integer sourceEpisodeNo,

        @Schema(description = "저장된 원문 인용문 존재 여부", example = "true")
        boolean hasEvidence
) {
}
