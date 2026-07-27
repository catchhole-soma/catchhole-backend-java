package org.monitoring.catchholebackend.domain.character.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "캐릭터 기본 정보와 현재 설정 전체 수정 요청")
public record CharacterUpdateRequest(
        @Schema(description = "캐릭터 이름", example = "수아")
        @NotBlank(message = "캐릭터 이름은 필수입니다.")
        @Size(max = 100, message = "캐릭터 이름은 100자 이하로 입력해주세요.")
        String name,

        @Schema(description = "작품 안에서의 역할", example = "주인공", nullable = true)
        @Size(max = 50, message = "캐릭터 역할은 50자 이하로 입력해주세요.")
        String roleLabel,

        @Schema(description = "현재 나이", example = "23", nullable = true)
        @PositiveOrZero(message = "현재 나이는 0 이상의 정수여야 합니다.")
        Integer currentAge,

        @Schema(description = "현재 레벨", example = "15", nullable = true)
        @PositiveOrZero(message = "현재 레벨은 0 이상의 정수여야 합니다.")
        Integer currentLevel,

        @Schema(description = "첫 등장 회차 번호", example = "1", nullable = true)
        @Positive(message = "첫 등장 회차 번호는 1 이상의 정수여야 합니다.")
        Integer firstAppearanceEpisodeNo,

        @Schema(description = "프로필 현재 설정 전체")
        @NotNull(message = "프로필 설정 목록은 필수입니다.")
        List<@NotNull(message = "프로필 설정 항목은 null일 수 없습니다.") @Valid CharacterSettingUpdateRequest> profile,

        @Schema(description = "스탯 현재 설정 전체")
        @NotNull(message = "스탯 설정 목록은 필수입니다.")
        List<@NotNull(message = "스탯 설정 항목은 null일 수 없습니다.") @Valid CharacterSettingUpdateRequest> stats,

        @Schema(description = "스킬 현재 설정 전체")
        @NotNull(message = "스킬 설정 목록은 필수입니다.")
        List<@NotNull(message = "스킬 설정 항목은 null일 수 없습니다.") @Valid CharacterSettingUpdateRequest> skills,

        @Schema(description = "아이템 현재 설정 전체")
        @NotNull(message = "아이템 설정 목록은 필수입니다.")
        List<@NotNull(message = "아이템 설정 항목은 null일 수 없습니다.") @Valid CharacterSettingUpdateRequest> items,

        @Schema(description = "상태 현재 설정 전체")
        @NotNull(message = "상태 설정 목록은 필수입니다.")
        List<@NotNull(message = "상태 설정 항목은 null일 수 없습니다.") @Valid CharacterSettingUpdateRequest> statuses
) {
}
