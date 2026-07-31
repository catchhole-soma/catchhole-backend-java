package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "회차 전체 원문 기준 캐릭터 설정 근거 범위")
public record CharacterFactEvidenceSpanResponse(
        @Schema(
                description = "AI가 원문에서 복사한 근거 문장",
                example = "비요른은 바바리안의 힘을 각성했다.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String quote,

        @Schema(
                description = "회차 전체 원문 기준 Unicode code point 시작 offset",
                example = "1204",
                nullable = true
        )
        Integer startOffset,

        @Schema(
                description = "회차 전체 원문 기준 Unicode code point 끝 offset(exclusive)",
                example = "1223",
                nullable = true
        )
        Integer endOffset
) {
}
