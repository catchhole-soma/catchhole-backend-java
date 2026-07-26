package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "캐릭터 기본 정보와 현재 설정 전체 응답")
public record CharacterDetailResponse(
        @Schema(description = "캐릭터 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d111")
        UUID id,

        @Schema(description = "캐릭터 이름", example = "수아")
        String name,

        @Schema(description = "작품 안에서의 역할", example = "주인공", nullable = true)
        String roleLabel,

        @Schema(description = "현재 나이", example = "23", nullable = true)
        Integer currentAge,

        @Schema(description = "현재 나이에 대응하는 Fact와 원문 근거 정보", nullable = true)
        CharacterFactReferenceResponse currentAgeFact,

        @Schema(description = "현재 레벨", example = "15", nullable = true)
        Integer currentLevel,

        @Schema(description = "현재 레벨에 대응하는 Fact와 원문 근거 정보", nullable = true)
        CharacterFactReferenceResponse currentLevelFact,

        @Schema(description = "첫 등장 회차", nullable = true)
        CharacterEpisodeResponse firstAppearanceEpisode,

        @Schema(description = "프로필 현재 설정")
        List<CharacterSettingResponse> profile,

        @Schema(description = "스탯 현재 설정")
        List<CharacterSettingResponse> stats,

        @Schema(description = "스킬 현재 설정")
        List<CharacterSettingResponse> skills,

        @Schema(description = "아이템 현재 설정")
        List<CharacterSettingResponse> items,

        @Schema(description = "상태 현재 설정")
        List<CharacterSettingResponse> statuses
) {
}
