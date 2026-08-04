package org.monitoring.catchholebackend.domain.aitoken.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;

@Schema(description = "사용되지 않은 AI 토큰 예약 해제 요청")
public record AiTokenReleaseRequest(
        @Schema(
                description = "사용량을 확인할 수 없어 예약을 해제하는 결과",
                example = "USAGE_UNAVAILABLE",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull AiTokenUsageOutcome outcome
) {
}
