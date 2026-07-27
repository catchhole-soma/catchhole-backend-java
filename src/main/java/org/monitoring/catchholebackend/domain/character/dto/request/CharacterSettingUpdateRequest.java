package org.monitoring.catchholebackend.domain.character.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;

@Schema(description = "캐릭터 현재 설정 항목 수정 요청")
public record CharacterSettingUpdateRequest(
        @Schema(description = "현재 설정의 canonical key", example = "stats.strength")
        @NotBlank(message = "캐릭터 설정 key는 필수입니다.")
        @Size(max = 150, message = "캐릭터 설정 key는 150자 이하로 입력해주세요.")
        String key,

        @Schema(description = "화면과 검색에서 사용하는 설정 표시값", example = "42", nullable = true)
        String value,

        @Schema(description = "설정 표시값 타입", example = "NUMBER")
        @NotNull(message = "캐릭터 설정 값 타입은 필수입니다.")
        SettingValueType valueType,

        @Schema(description = "스킬, 아이템, 상태 등 복합 설정의 세부 속성")
        @NotNull(message = "세부 속성 목록은 필수입니다.")
        List<@NotNull(message = "세부 속성 항목은 null일 수 없습니다.") @Valid CharacterSettingPropertyRequest> properties
) {
}
