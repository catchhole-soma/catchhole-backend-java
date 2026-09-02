package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "AI Worker 캐릭터명 매칭과 1차 상태 문맥용 기존 캐릭터 payload")
public record WorkerAnalysisKnownCharacterPayload(
        @Schema(description = "캐릭터 ID")
        UUID characterId,

        @Schema(description = "캐릭터 대표 이름")
        String name,

        @Schema(description = "회차 시작 전에 활성화된 캐릭터 STATUS 목록")
        List<ActiveStatus> activeStatuses
) {

    @Schema(name = "WorkerAnalysisActiveCharacterStatusPayload", description = "1차 추출 문맥용 활성 캐릭터 STATUS")
    public record ActiveStatus(
            @Schema(description = "캐릭터 STATUS canonical Fact key", example = "status.오른발_부상")
            String factKey,

            @Schema(
                    description = "사람이 읽을 수 있는 현재 상태값. provenance가 없는 legacy 값은 null일 수 있습니다.",
                    nullable = true,
                    example = "오른발이 심하게 다쳐 걷기 어려움"
            )
            String factValue
    ) {
    }
}
