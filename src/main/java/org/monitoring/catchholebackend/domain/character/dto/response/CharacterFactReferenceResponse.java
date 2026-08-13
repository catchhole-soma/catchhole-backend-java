package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "캐릭터 현재 snapshot 값을 구성한 원본 Fact 참조")
public record CharacterFactReferenceResponse(
        @Schema(
                description = "원본 CharacterFact ID. 설정 상세·검색과 원문 근거 조회 식별자로 사용합니다.",
                example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d333",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        UUID characterFactId,

        @Schema(description = "원본 Fact 출처 회차 ID. 수동 입력은 null입니다.", nullable = true)
        UUID sourceEpisodeId,

        @Schema(description = "원본 Fact 출처 회차 번호. 수동 입력은 null입니다.", example = "12", nullable = true)
        Integer sourceEpisodeNo,

        @Schema(
                description = "설정 상세와 원문 근거 패널에서 조회 가능한 저장 근거 존재 여부",
                example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        boolean hasEvidence
) {
}
