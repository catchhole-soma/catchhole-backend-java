package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "캐릭터 현재·과거 Fact의 분석 당시 원문 근거")
public record CharacterFactEvidenceResponse(
        @Schema(description = "캐릭터 Fact ID", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID characterFactId,

        @Schema(description = "Fact를 만든 설정 후보 ID", nullable = true)
        UUID sourceCandidateId,

        @Schema(description = "근거 출처 회차", nullable = true)
        CharacterFactEvidenceEpisodeResponse episode,

        @Schema(
                description = "분석 당시 회차 전체 원문. 저장소 조회 실패 또는 출처가 없으면 null",
                nullable = true
        )
        String content,

        @Schema(description = "원문 근거 범위 목록", requiredMode = Schema.RequiredMode.REQUIRED)
        List<CharacterFactEvidenceSpanResponse> evidenceSpans
) {
}
