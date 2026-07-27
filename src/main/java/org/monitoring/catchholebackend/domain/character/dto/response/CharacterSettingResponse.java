package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;

@Schema(description = "캐릭터 현재 설정의 사용자용 응답")
public record CharacterSettingResponse(
        @Schema(
                description = "현재 설정 CharacterFact ID. MVP 후속 PR의 상세·원문 근거 조회 식별자",
                example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d333"
        )
        UUID characterFactId,

        @Schema(description = "현재 설정 canonical key", example = "stats.strength")
        String key,

        @Schema(description = "화면 표시명", example = "근력")
        String displayName,

        @Schema(description = "사용자용 설정 표시값", example = "42", nullable = true)
        String value,

        @Schema(description = "설정 표시값 타입", example = "NUMBER")
        SettingValueType valueType,

        @Schema(description = "복합 설정의 사용자용 세부 속성")
        List<CharacterSettingPropertyResponse> properties,

        @Schema(description = "후속 원문 근거 패널에서 선택 가능한 근거 존재 여부", example = "true")
        boolean hasEvidence
) {
}
