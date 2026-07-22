package org.monitoring.catchholebackend.domain.character.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;

@Schema(description = "캐릭터 복합 설정의 세부 속성 수정 요청")
public record CharacterSettingPropertyRequest(
        @Schema(description = "세부 속성 key", example = "level")
        @NotBlank(message = "세부 속성 key는 필수입니다.")
        @Size(max = 100, message = "세부 속성 key는 100자 이하로 입력해주세요.")
        String key,

        @Schema(description = "사용자용 세부 속성 값", example = "3", nullable = true)
        String value,

        @Schema(description = "세부 속성 값 타입", example = "NUMBER")
        @NotNull(message = "세부 속성 값 타입은 필수입니다.")
        SettingValueType valueType
) {
}
