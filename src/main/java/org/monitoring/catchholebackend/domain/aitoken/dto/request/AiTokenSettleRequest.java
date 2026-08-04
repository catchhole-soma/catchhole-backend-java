package org.monitoring.catchholebackend.domain.aitoken.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;

@Schema(description = "AI provider 응답의 실제 토큰 사용량 정산 요청")
public record AiTokenSettleRequest(
        @Schema(description = "캐시 입력을 포함한 전체 입력 토큰 수", example = "6400", minimum = "0")
        @Min(0) long inputTokens,
        @Schema(description = "입력 토큰 중 캐시 가격이 적용된 토큰 수", example = "4096", minimum = "0")
        @Min(0) long cachedInputTokens,
        @Schema(description = "출력 토큰 수", example = "900", minimum = "0")
        @Min(0) long outputTokens,
        @Schema(description = "provider 호출 결과", example = "SUCCESS", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull AiTokenUsageOutcome outcome
) {
}
