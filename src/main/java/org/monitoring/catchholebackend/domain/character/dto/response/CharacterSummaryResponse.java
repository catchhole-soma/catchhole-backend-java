package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "설정DB 캐릭터 카드 요약 응답")
public record CharacterSummaryResponse(
        @Schema(description = "캐릭터 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d111")
        UUID id,

        @Schema(description = "캐릭터 이름", example = "수아")
        String name,

        @Schema(description = "현재 나이", example = "23", nullable = true)
        Integer currentAge,

        @Schema(description = "대표 속성 표시명", example = "레벨", nullable = true)
        String representativeAttributeLabel,

        @Schema(description = "대표 속성 표시값", example = "15", nullable = true)
        String representativeAttributeValue,

        @Schema(description = "첫 등장 회차 번호", example = "1", nullable = true)
        Integer firstAppearanceEpisodeNo
) {
}
