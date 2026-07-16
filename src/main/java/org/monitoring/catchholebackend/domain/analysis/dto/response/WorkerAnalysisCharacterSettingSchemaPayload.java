package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;

@Schema(description = "AI Worker의 캐릭터 설정 attribute 해석 schema")
public record WorkerAnalysisCharacterSettingSchemaPayload(
        @Schema(description = "저장에 사용할 canonical schema key")
        String schemaKey,

        @Schema(description = "사람이 읽을 수 있는 schema 이름")
        String displayName,

        @Schema(description = "attributeName 패턴", nullable = true)
        String attributePattern,

        @Schema(description = "schema key로 정규화할 수 있는 별칭 목록")
        List<String> aliases,

        @Schema(description = "schema 값 타입")
        SettingValueType valueType
) {
}
