package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;

@Schema(description = "캐릭터 현재 설정의 사용자용 응답")
public record CharacterSettingResponse(
        @Schema(
                description = "호환용 대표 CharacterFact ID. sourceFacts의 마지막 원소와 같으며 새 클라이언트는 sourceFacts를 사용합니다.",
                example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d333",
                nullable = true,
                deprecated = true
        )
        @Deprecated
        UUID characterFactId,

        @Schema(description = "현재 설정 canonical key", example = "stats.strength")
        String key,

        @Schema(description = "화면 표시명", example = "근력")
        String displayName,

        @Schema(
                description = "canonical key의 동적 suffix 수정 가능 여부",
                example = "false",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        boolean attributeNameEditable,

        @Schema(
                description = "동적 key 수정 시 서버가 허용하는 고정 prefix",
                example = "skill.",
                nullable = true
        )
        String attributeNamePrefix,

        @Schema(
                description = "화면 표시명 수정 가능 여부",
                example = "false",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        boolean displayNameEditable,

        @Schema(description = "사용자용 설정 표시값", example = "42", nullable = true)
        String value,

        @Schema(description = "설정 표시값 타입", example = "NUMBER")
        SettingValueType valueType,

        @Schema(description = "복합 설정의 사용자용 세부 속성")
        List<CharacterSettingPropertyResponse> properties,

        @Schema(
                description = "호환용 원문 근거 존재 여부. sourceFacts 중 하나라도 근거가 있으면 true입니다.",
                example = "true",
                deprecated = true
        )
        @Deprecated
        boolean hasEvidence,

        @Schema(description = "현재 snapshot 설정을 구성한 원본 Fact 목록. MERGE 결과는 여러 항목을 가질 수 있습니다.")
        List<CharacterFactReferenceResponse> sourceFacts
) {
}
