package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "캐릭터 기본 정보 값에 대응하는 현재 Fact 참조")
public record CharacterFactReferenceResponse(
        @Schema(
                description = "현재 CharacterFact ID. MVP 후속 PR의 CharacterFact 상세·원문 근거 조회 식별자로 사용합니다.",
                example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d333",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        UUID characterFactId,

        @Schema(
                description = "후속 원문 근거 패널에서 선택 가능한 근거 존재 여부",
                example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        boolean hasEvidence
) {
}
