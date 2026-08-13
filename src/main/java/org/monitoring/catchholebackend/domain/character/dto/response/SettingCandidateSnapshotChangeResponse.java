package org.monitoring.catchholebackend.domain.character.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterSnapshotAction;

@Schema(description = "후보 확정 시 현재 캐릭터 snapshot에 적용될 변경")
public record SettingCandidateSnapshotChangeResponse(
        CharacterSnapshotAction action,
        CharacterFactType factType,
        String factKey,
        @Schema(nullable = true)
        String beforeFactValue,
        @Schema(nullable = true, implementation = JsonNode.class)
        Object beforeValueJson,
        @Schema(nullable = true)
        String proposedFactValue,
        @Schema(nullable = true, implementation = JsonNode.class)
        Object proposedValueJson
) {
}
