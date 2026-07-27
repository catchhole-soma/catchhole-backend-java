package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;

@Schema(description = "보관 캐릭터 복구 응답")
public record CharacterRestoreResponse(
        @Schema(description = "복구된 캐릭터 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d111")
        UUID id,

        @Schema(description = "복구된 캐릭터 상태", example = "ACTIVE")
        CharacterStatus status
) {
}
