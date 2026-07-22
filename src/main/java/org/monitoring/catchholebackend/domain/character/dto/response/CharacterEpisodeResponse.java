package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "캐릭터와 연결된 회차 요약 응답")
public record CharacterEpisodeResponse(
        @Schema(description = "회차 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d222")
        UUID id,

        @Schema(description = "회차 번호", example = "1")
        Integer episodeNo
) {
}
