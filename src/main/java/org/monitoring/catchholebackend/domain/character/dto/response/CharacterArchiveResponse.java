package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;

@Schema(description = "캐릭터 삭제 버튼 처리 결과. 데이터는 유지하고 보관 상태로 전환합니다.")
public record CharacterArchiveResponse(
        @Schema(description = "캐릭터 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d111")
        UUID id,

        @Schema(description = "변경된 캐릭터 상태", example = "ARCHIVED")
        CharacterStatus status
) {
}
