package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;

@Schema(description = "캐릭터 복합 설정의 사용자용 세부 속성")
public record CharacterSettingPropertyResponse(
        @Schema(description = "세부 속성 key", example = "level")
        String key,

        @Schema(description = "세부 속성 표시명", example = "레벨")
        String displayName,

        @Schema(description = "세부 속성 표시값", example = "3", nullable = true)
        String value,

        @Schema(description = "세부 속성 값 타입", example = "NUMBER")
        SettingValueType valueType
) {
}
