package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "캐릭터 설정 원문 근거의 출처 회차")
public record CharacterFactEvidenceEpisodeResponse(
        @Schema(description = "회차 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID episodeId,

        @Schema(description = "회차 번호", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
        int episodeNo,

        @Schema(description = "회차 제목", example = "첫 번째 전투", nullable = true)
        String title
) {
}
