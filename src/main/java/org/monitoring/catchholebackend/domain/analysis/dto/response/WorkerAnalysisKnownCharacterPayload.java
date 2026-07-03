package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "AI Worker 캐릭터명 매칭용 기존 캐릭터 payload")
public record WorkerAnalysisKnownCharacterPayload(
        @Schema(description = "캐릭터 ID")
        UUID characterId,

        @Schema(description = "캐릭터 대표 이름")
        String name
) {
}
